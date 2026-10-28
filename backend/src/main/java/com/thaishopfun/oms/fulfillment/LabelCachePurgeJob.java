package com.thaishopfun.oms.fulfillment;

import com.thaishopfun.oms.tenant.TenantContext;
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

/** Purges cached label PDFs once shipment.label_cached_until has passed (≤7 days after SHIPPED). */
@Component
public class LabelCachePurgeJob {

  private final JdbcTemplate jdbc;
  private final ShipmentLabelCacheRepository cache;
  private final TransactionTemplate tx;
  private final Clock clock;
  private final int batchSize;

  public LabelCachePurgeJob(
      JdbcTemplate jdbc,
      ShipmentLabelCacheRepository cache,
      PlatformTransactionManager manager,
      Clock clock,
      @Value("${oms.fulfillment.label-cache-purge.batch-size:500}") int batchSize) {
    if (batchSize < 1 || batchSize > 2000) {
      throw new IllegalArgumentException("label-cache purge batch-size must be 1..2000");
    }
    this.jdbc = jdbc;
    this.cache = cache;
    this.clock = clock;
    this.batchSize = batchSize;
    tx = new TransactionTemplate(manager);
    tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  public int runOnce() {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException("Label cache purge must run outside a transaction");
    }
    UUID previousTenant = TenantContext.tenantId();
    UUID previousUser = TenantContext.userId();
    Instant now = clock.instant();
    int total = 0;
    try {
      TenantContext.clear();
      List<UUID> tenants =
          jdbc.query(
              "SELECT tenant_id FROM list_tenants_with_label_cache_due(?)",
              (rs, row) -> rs.getObject(1, UUID.class),
              java.sql.Timestamp.from(now));
      for (UUID tenantId : tenants) {
        TenantContext.set(tenantId, null);
        int removed;
        do {
          removed = tx.execute(status -> cache.purgeExpired(now));
          total += removed;
        } while (removed >= batchSize);
      }
      return total;
    } finally {
      if (previousTenant == null) {
        TenantContext.clear();
      } else {
        TenantContext.set(previousTenant, previousUser);
      }
    }
  }
}
