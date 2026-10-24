package com.thaishopfun.oms.pii.lifecycle;

import com.thaishopfun.oms.auth.UuidV7;
import com.thaishopfun.oms.order.OrderRecipientRepository;
import com.thaishopfun.oms.tenant.TenantContext;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Discovery exposes identifiers only. All reads and writes run under tenant FORCE RLS. */
@Component
public class PiiLifecycleJob {
  private final JdbcTemplate jdbc;
  private final OrderRecipientRepository recipients;
  private final TransactionTemplate tx;
  private final Clock clock;
  private final int batchSize;

  public PiiLifecycleJob(
      JdbcTemplate jdbc,
      OrderRecipientRepository recipients,
      PlatformTransactionManager manager,
      Clock clock,
      @Value("${oms.pii.redaction.batch-size:500}") int batchSize) {
    if (batchSize < 1 || batchSize > 1000)
      throw new IllegalArgumentException("PII batch-size must be 1..1000");
    this.jdbc = jdbc;
    this.recipients = recipients;
    this.clock = clock;
    this.batchSize = batchSize;
    tx = new TransactionTemplate(manager);
    tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  public int runOnce() {
    if (TransactionSynchronizationManager.isActualTransactionActive())
      throw new IllegalStateException("PII job must run outside a transaction");
    UUID previous = TenantContext.tenantId();
    UUID user = TenantContext.userId();
    Instant now = clock.instant();
    int count = 0;
    try {
      TenantContext.clear();
      // No tenant limit: a busy tenant must not starve other shops.
      List<UUID> tenants =
          jdbc.query(
              "SELECT tenant_id FROM list_tenants_with_pii_due(?)",
              (rs, row) -> rs.getObject(1, UUID.class),
              Timestamp.from(now));
      for (UUID tenant : tenants) {
        TenantContext.set(tenant, null);
        int changed;
        do {
          changed = tx.execute(status -> processTenant(now));
          count += changed;
        } while (changed > 0);
      }
      return count;
    } finally {
      if (previous == null) TenantContext.clear();
      else TenantContext.set(previous, user);
    }
  }

  private int processTenant(Instant now) {
    List<UUID> ids =
        jdbc.query(
            """
        SELECT order_id FROM order_recipient
        WHERE pii_status = 'ACTIVE' AND redact_after <= ?
        ORDER BY redact_after, order_id LIMIT ? FOR UPDATE SKIP LOCKED
        """,
            (rs, row) -> rs.getObject(1, UUID.class),
            Timestamp.from(now),
            batchSize);
    ids.forEach(recipients::redact);
    if (!ids.isEmpty()) {
      jdbc.update(
          """
          INSERT INTO audit_log (id, tenant_id, actor_type, action, entity_type, "after")
          VALUES (?, ?, 'SYSTEM', 'PII_REDACTED', 'order_recipient', jsonb_build_object('count', ?::int))
          """,
          UuidV7.generate(),
          TenantContext.requireTenantId(),
          ids.size());
    }
    return ids.size()
        + scrub(
            "inbox_event",
            "status IN ('PROCESSED', 'DEAD')",
            "coalesce(processed_at, received_at)",
            now)
        + scrub("outbox_event", "status IN ('SENT', 'DEAD')", "coalesce(sent_at, created_at)", now);
  }

  private int scrub(String table, String statuses, String age, Instant now) {
    // Constants only; no identifiers originate from requests. No payload is loaded into Java.
    return jdbc.update(
        "UPDATE "
            + table
            + " SET payload = "
            + "payload #- '{data,recipient,name}' #- '{data,recipient,phone}' #- '{data,recipient,address}', "
            + "payload_scrubbed_at = ? WHERE id IN (SELECT id FROM "
            + table
            + " WHERE payload_scrubbed_at IS NULL AND "
            + statuses
            + " AND "
            + age
            + " <= ?::timestamptz - interval '7 days' ORDER BY "
            + age
            + ", id LIMIT ? FOR UPDATE SKIP LOCKED)",
        Timestamp.from(now),
        Timestamp.from(now),
        batchSize);
  }
}
