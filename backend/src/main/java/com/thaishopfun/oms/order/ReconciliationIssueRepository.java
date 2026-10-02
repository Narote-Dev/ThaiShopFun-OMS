package com.thaishopfun.oms.order;

import com.thaishopfun.oms.auth.UuidV7;
import com.thaishopfun.oms.tenant.TenantContext;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ReconciliationIssueRepository {

  private final JdbcTemplate jdbc;

  public ReconciliationIssueRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
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
   * One open issue per inbox event when {@code order_id} is unknown. The partial unique index
   * {@code reconciliation_issue_open_key} uses NULLS NOT DISTINCT, so we dedupe by {@code
   * details.inbox_event_id} without a migration.
   */
  public void upsertOpenWithoutOrder(UUID runId, String rule, String detailsJson) {
    UUID tenantId = TenantContext.requireTenantId();
    String details = detailsJson == null ? "{}" : detailsJson;
    String inboxEventId = extractInboxEventId(details);
    if (inboxEventId != null) {
      Integer existing =
          jdbc.queryForObject(
              """
              SELECT count(*) FROM reconciliation_issue
              WHERE tenant_id = ? AND rule = ? AND status <> 'RESOLVED'
                AND details->>'inbox_event_id' = ?
              """,
              Integer.class,
              tenantId,
              rule,
              inboxEventId);
      if (existing != null && existing > 0) {
        jdbc.update(
            """
            UPDATE reconciliation_issue
            SET details = ?::jsonb, updated_at = now()
            WHERE tenant_id = ? AND rule = ? AND status <> 'RESOLVED'
              AND details->>'inbox_event_id' = ?
            """,
            details,
            tenantId,
            rule,
            inboxEventId);
        return;
      }
    }
    jdbc.update(
        """
        INSERT INTO reconciliation_issue (id, tenant_id, run_id, rule, order_id, details, status)
        VALUES (?, ?, ?, ?, NULL, ?::jsonb, 'OPEN')
        """,
        UuidV7.generate(),
        tenantId,
        runId,
        rule,
        details);
  }

  private static String extractInboxEventId(String detailsJson) {
    int key = detailsJson.indexOf("\"inbox_event_id\"");
    if (key < 0) {
      return null;
    }
    int start = detailsJson.indexOf('"', key + 16);
    if (start < 0) {
      return null;
    }
    int end = detailsJson.indexOf('"', start + 1);
    if (end < 0) {
      return null;
    }
    return detailsJson.substring(start + 1, end);
  }
}
