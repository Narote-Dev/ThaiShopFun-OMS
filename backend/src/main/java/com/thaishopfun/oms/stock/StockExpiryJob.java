package com.thaishopfun.oms.stock;

import com.thaishopfun.oms.stock.ReservationEngine.ExpiryBatch;
import com.thaishopfun.oms.tenant.TenantContext;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Returns expired holds to stock. One run: ask the V6 SECURITY DEFINER function which tenants have
 * ACTIVE reservations past {@code now} (ids only, any entitlement status), then one transaction per
 * tenant and batch with that tenant's context. Each batch locks inventory in id order and re-checks
 * {@code status = 'ACTIVE' AND expires_at <= now} under the lock, so concurrent runs on several
 * instances never release a row twice.
 */
@Component
public class StockExpiryJob {

  public static final String EXPIRED_METRIC = "oms.stock.expired";

  /** Tenant rounds per run. Each round asks the function again for up to tenant-limit tenants. */
  static final int MAX_ROUNDS = 10;

  private static final Logger log = LoggerFactory.getLogger(StockExpiryJob.class);

  private final ReservationEngine engine;
  private final StockProperties properties;
  private final JdbcTemplate jdbc;
  private final TransactionTemplate claimTx;
  private final Clock clock;
  private final Counter expiredCounter;

  StockExpiryJob(
      ReservationEngine engine,
      StockProperties properties,
      JdbcTemplate jdbc,
      PlatformTransactionManager transactions,
      Clock clock,
      MeterRegistry meters) {
    this.engine = engine;
    this.properties = properties;
    this.jdbc = jdbc;
    this.claimTx = new TransactionTemplate(transactions);
    this.claimTx.setReadOnly(true);
    this.claimTx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    this.clock = clock;
    this.expiredCounter = Counter.builder(EXPIRED_METRIC).register(meters);
  }

  /** One pass at the clock's current instant. Returns the rows moved to EXPIRED. */
  public int runOnce() {
    return runOnce(clock.instant());
  }

  int runOnce(Instant now) {
    // Step 0: Each tenant gets its own transaction, so the job cannot run inside another one.
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException("stock expiry must run outside a transaction");
    }
    // Step 1: Keep the caller's context; the job switches tenants per transaction.
    UUID previousTenant = TenantContext.tenantId();
    UUID previousUser = TenantContext.userId();
    int expired = 0;
    try {
      for (int round = 0; round < MAX_ROUNDS; round++) {
        // Step 2: Tenant ids only, from a transaction with no tenant bound.
        TenantContext.clear();
        List<UUID> tenants = tenantsWithExpired(now);
        if (tenants.isEmpty()) {
          break;
        }
        int roundExpired = 0;
        boolean more = tenants.size() >= properties.getExpiry().getTenantLimit();
        for (UUID tenantId : tenants) {
          try {
            TenantRun run = expireTenant(tenantId, now);
            roundExpired += run.expired();
            more |= run.capped();
          } catch (RuntimeException ex) {
            log.error("stock expiry failed for tenant {}", tenantId, ex);
          }
        }
        expired += roundExpired;
        // Step 3: Another round only if work was left behind and this round made progress.
        if (roundExpired == 0 || !more) {
          break;
        }
      }
    } finally {
      restore(previousTenant, previousUser);
    }
    if (expired > 0) {
      expiredCounter.increment(expired);
      log.info("stock expiry returned {} reservation rows", expired);
    }
    return expired;
  }

  private record TenantRun(int expired, boolean capped) {}

  private List<UUID> tenantsWithExpired(Instant now) {
    List<UUID> ids =
        claimTx.execute(
            status ->
                jdbc.queryForList(
                    "SELECT tenant_id FROM list_tenants_with_expired_reservations(?, ?)",
                    UUID.class,
                    OffsetDateTime.ofInstant(now, ZoneOffset.UTC),
                    properties.getExpiry().getTenantLimit()));
    Set<UUID> unique = new LinkedHashSet<>(ids == null ? List.of() : ids);
    return List.copyOf(unique);
  }

  private TenantRun expireTenant(UUID tenantId, Instant now) {
    TenantContext.set(tenantId, null);
    try {
      int expired = 0;
      int batchSize = properties.getExpiry().getBatchSize();
      for (int batch = 0; batch < properties.getExpiry().getMaxBatchesPerTenant(); batch++) {
        // Step 1: One short transaction per batch. A full batch means more may be waiting.
        ExpiryBatch result = engine.expireBatch(now, batchSize);
        expired += result.expired();
        if (result.candidates() < batchSize) {
          return new TenantRun(expired, false);
        }
      }
      return new TenantRun(expired, true);
    } finally {
      TenantContext.clear();
    }
  }

  private static void restore(UUID tenantId, UUID userId) {
    if (tenantId == null) {
      TenantContext.clear();
    } else {
      TenantContext.set(tenantId, userId);
    }
  }
}
