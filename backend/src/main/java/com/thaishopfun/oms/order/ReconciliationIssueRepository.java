package com.thaishopfun.oms.order;

import com.thaishopfun.oms.auth.UuidV7;
import com.thaishopfun.oms.tenant.TenantContext;
import java.sql.Connection;
import java.sql.PreparedStatement;
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
   * events into {@code details.events} (cap {@value MAX_EVENTS}) on conflict, deduped by {@code
   * inbox_event_id}.
   *
   * <p>When more than {@value MAX_EVENTS} distinct events exist, {@code details.events} keeps the
   * {@value MAX_EVENTS} most recently appended (highest merge ordinal); {@code details.count}
   * remains the full distinct total.
   */
  public void upsertOpenWithoutOrder(UUID inboxEventId, String rule, String externalOrderId) {
    UUID tenantId = TenantContext.requireTenantId();
    jdbc.execute(
        (Connection connection) -> {
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "SELECT pg_advisory_xact_lock(hashtext(?::text), hashtext(?::text))")) {
            statement.setString(1, tenantId.toString());
            statement.setString(2, rule);
            statement.execute();
          }
          return null;
        });
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
          details = (
            WITH merged AS (
              SELECT value, ord
              FROM jsonb_array_elements(
                COALESCE(reconciliation_issue.details->'events', '[]'::jsonb)
                || COALESCE(EXCLUDED.details->'events', '[]'::jsonb)
              ) WITH ORDINALITY AS t(value, ord)
            ),
            deduped AS (
              SELECT (array_agg(m.value ORDER BY m.ord))[1] AS value, MIN(m.ord) AS min_ord
              FROM merged m
              GROUP BY m.value->>'inbox_event_id'
            ),
            capped AS (
              SELECT d.value, d.min_ord
              FROM deduped d
              ORDER BY d.min_ord DESC
              LIMIT """
            + MAX_EVENTS
            + """
            ),
            distinct_count AS (
              SELECT count(*)::int AS cnt FROM deduped
            )
            SELECT jsonb_build_object(
              'events',
              COALESCE(
                (SELECT jsonb_agg(c.value ORDER BY c.min_ord) FROM capped c),
                '[]'::jsonb
              ),
              'count',
              (SELECT cnt FROM distinct_count)
            )
          ),
          updated_at = now()
        """,
        UuidV7.generate(),
        tenantId,
        inboxEventId,
        rule,
        initialJson);
  }
}
