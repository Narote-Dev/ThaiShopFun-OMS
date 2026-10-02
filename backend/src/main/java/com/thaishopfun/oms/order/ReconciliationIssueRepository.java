package com.thaishopfun.oms.order;

import com.thaishopfun.oms.auth.UuidV7;
import com.thaishopfun.oms.tenant.TenantContext;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

@Repository
public class ReconciliationIssueRepository {

  private static final int MAX_EVENTS = 100;

  private final JdbcTemplate jdbc;
  private final JsonMapper json;

  public ReconciliationIssueRepository(JdbcTemplate jdbc, JsonMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  /** Upserts one open issue per (tenant, rule, order). */
  public void upsertOpen(UUID runId, String rule, UUID orderId, String detailsJson) {
    UUID tenantId = TenantContext.requireTenantId();
    String details = detailsJson == null ? "{}" : detailsJson;
    int updated =
        jdbc.update(
            """
            UPDATE reconciliation_issue
            SET details = ?::jsonb, updated_at = now()
            WHERE tenant_id = ? AND rule = ? AND order_id = ? AND status <> 'RESOLVED'
            """,
            details,
            tenantId,
            rule,
            orderId);
    if (updated == 0) {
      jdbc.update(
          """
          INSERT INTO reconciliation_issue (id, tenant_id, run_id, rule, order_id, details, status)
          VALUES (?, ?, ?, ?, ?, ?::jsonb, 'OPEN')
          """,
          UuidV7.generate(),
          tenantId,
          runId,
          rule,
          orderId,
          details);
    }
  }

  /**
   * One open shop-level issue per (tenant, rule) when {@code order_id} is unknown. Appends inbox
   * events into {@code details.events} (cap {@value MAX_EVENTS}) on conflict, deduped by {@code
   * inbox_event_id}.
   */
  public void upsertOpenWithoutOrder(UUID inboxEventId, String rule, String externalOrderId) {
    UUID tenantId = TenantContext.requireTenantId();
    ObjectNode event = json.createObjectNode();
    event.put("inbox_event_id", inboxEventId.toString());
    if (externalOrderId != null && !externalOrderId.isBlank()) {
      event.put("external_order_id", externalOrderId);
    }

    List<String> existingRows =
        jdbc.query(
            """
            SELECT details::text FROM reconciliation_issue
            WHERE tenant_id = ? AND rule = ? AND order_id IS NULL AND status <> 'RESOLVED'
            LIMIT 1
            """,
            (rs, row) -> rs.getString(1),
            tenantId,
            rule);
    String existingJson = existingRows.isEmpty() ? null : existingRows.get(0);
    if (existingJson == null) {
      ObjectNode initial = json.createObjectNode();
      ArrayNode events = json.createArrayNode();
      events.add(event);
      initial.set("events", events);
      initial.put("count", 1);
      jdbc.update(
          """
          INSERT INTO reconciliation_issue (id, tenant_id, run_id, rule, order_id, details, status)
          VALUES (?, ?, ?, ?, NULL, ?::jsonb, 'OPEN')
          """,
          UuidV7.generate(),
          tenantId,
          inboxEventId,
          rule,
          json.writeValueAsString(initial));
      return;
    }

    ObjectNode details = (ObjectNode) json.readTree(existingJson);
    ArrayNode events = (ArrayNode) details.path("events");
    if (events == null || !events.isArray()) {
      events = json.createArrayNode();
      details.set("events", events);
    }
    if (containsEventId(events, inboxEventId.toString())) {
      return;
    }
    ArrayNode merged = json.createArrayNode();
    merged.add(event);
    for (JsonNode existing : events) {
      if (merged.size() >= MAX_EVENTS) {
        break;
      }
      merged.add(existing);
    }
    details.set("events", merged);
    details.put("count", merged.size());
    jdbc.update(
        """
        UPDATE reconciliation_issue
        SET details = ?::jsonb, updated_at = now()
        WHERE tenant_id = ? AND rule = ? AND order_id IS NULL AND status <> 'RESOLVED'
        """,
        json.writeValueAsString(details),
        tenantId,
        rule);
  }

  private static boolean containsEventId(ArrayNode events, String inboxEventId) {
    for (JsonNode node : events) {
      if (inboxEventId.equals(node.path("inbox_event_id").asString(null))) {
        return true;
      }
    }
    return false;
  }
}
