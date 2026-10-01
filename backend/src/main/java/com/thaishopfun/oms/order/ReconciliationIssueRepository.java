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

  public void upsertOpenWithoutOrder(UUID runId, String rule, String detailsJson) {
    UUID tenantId = TenantContext.requireTenantId();
    String details = detailsJson == null ? "{}" : detailsJson;
    int updated =
        jdbc.update(
            """
            UPDATE reconciliation_issue
            SET details = ?::jsonb, updated_at = now()
            WHERE tenant_id = ? AND rule = ? AND order_id IS NULL AND status <> 'RESOLVED'
            """,
            details,
            tenantId,
            rule);
    if (updated == 0) {
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
  }
}
