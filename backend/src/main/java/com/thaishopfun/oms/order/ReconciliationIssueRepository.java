package com.thaishopfun.oms.order;

import com.thaishopfun.oms.auth.UuidV7;
import com.thaishopfun.oms.tenant.TenantContext;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
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
   * events into {@code details.events} (cap {@value MAX_EVENTS}) on conflict.
   */
  public void upsertOpenWithoutOrder(UUID inboxEventId, String rule, String externalOrderId) {
    UUID tenantId = TenantContext.requireTenantId();
    ObjectNode event = json.createObjectNode();
    event.put("inbox_event_id", inboxEventId.toString());
    if (externalOrderId != null && !externalOrderId.isBlank()) {
      event.put("external_order_id", externalOrderId);
    }
    ArrayNode events = json.createArrayNode();
    events.add(event);
    ObjectNode initial = json.createObjectNode();
    initial.set("events", events);
    initial.put("count", 1);
    String initialJson = json.writeValueAsString(initial);

    jdbc.update(
        """
        INSERT INTO reconciliation_issue (id, tenant_id, run_id, rule, order_id, details, status)
        VALUES (?, ?, ?, ?, NULL, ?::jsonb, 'OPEN')
        ON CONFLICT (tenant_id, rule, order_id) WHERE (status <> 'RESOLVED')
        DO UPDATE SET
          details = jsonb_set(
            jsonb_set(
              reconciliation_issue.details,
              '{events}',
              (
                SELECT COALESCE(jsonb_agg(value), '[]'::jsonb)
                FROM (
                  SELECT value
                  FROM jsonb_array_elements(
                    COALESCE(reconciliation_issue.details->'events', '[]'::jsonb)
                    || COALESCE(EXCLUDED.details->'events', '[]'::jsonb)
                  ) WITH ORDINALITY AS t(value, ord)
                  ORDER BY ord DESC
                  LIMIT ?
                ) trimmed
              ),
              true
            ),
            '{count}',
            to_jsonb(COALESCE((reconciliation_issue.details->>'count')::int, 0) + 1),
            true
          ),
          updated_at = now()
        """,
        UuidV7.generate(),
        tenantId,
        inboxEventId,
        rule,
        initialJson,
        MAX_EVENTS);
  }
}
