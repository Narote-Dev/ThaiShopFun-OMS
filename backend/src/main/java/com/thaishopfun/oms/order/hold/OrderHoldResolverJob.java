package com.thaishopfun.oms.order.hold;

import com.thaishopfun.oms.listing.ChannelListingRepository;
import com.thaishopfun.oms.order.hold.OrderHoldResolver.Outcome;
import com.thaishopfun.oms.tenant.TenantContext;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class OrderHoldResolverJob {

  private static final Logger log = LoggerFactory.getLogger(OrderHoldResolverJob.class);
  public static final String DEFERRED_COUNTER = "oms.order.hold_resolver.deferred";
  public static final String BACKOFF_GAUGE = "oms.order.hold_resolver.backoff_active";

  public record ReevalSummary(int released, int outOfStock, int stillHeld, int deferred) {

    public static ReevalSummary zero() {
      return new ReevalSummary(0, 0, 0, 0);
    }
  }

  private final OrderHoldResolver resolver;
  private final ChannelListingRepository listings;
  private final OrderHoldProperties properties;
  private final OrderHoldRetryRepository retries;
  private final TransactionTemplate tenantReadTx;
  private final TransactionTemplate tenantWriteTx;
  private final Clock clock;
  private final Counter deferredCounter;
  private final AtomicLong backoffGauge = new AtomicLong();

  public OrderHoldResolverJob(
      OrderHoldResolver resolver,
      ChannelListingRepository listings,
      OrderHoldProperties properties,
      OrderHoldRetryRepository retries,
      PlatformTransactionManager transactions,
      Clock clock,
      MeterRegistry meters) {
    this.resolver = resolver;
    this.listings = listings;
    this.properties = properties;
    this.retries = retries;
    this.clock = clock;
    this.tenantReadTx = new TransactionTemplate(transactions);
    this.tenantReadTx.setReadOnly(true);
    this.tenantReadTx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    this.tenantWriteTx = new TransactionTemplate(transactions);
    this.tenantWriteTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    this.tenantWriteTx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    this.deferredCounter = Counter.builder(DEFERRED_COUNTER).register(meters);
    Gauge.builder(BACKOFF_GAUGE, backoffGauge, AtomicLong::get).register(meters);
  }

  public ReevalSummary reevalAfterMapping(UUID channelAccountId, String externalSkuId) {
    assertNoActiveTransaction("reevalAfterMapping");
    return reevalForListing(channelAccountId, externalSkuId, properties.getReevalCap());
  }

  public ReevalSummary reevalForListing(UUID channelAccountId, String externalSkuId, int cap) {
    assertNoActiveTransaction("reevalForListing");
    UUID tenantId = TenantContext.requireTenantId();
    List<UUID> orderIds =
        tenantReadTx.execute(
            status ->
                listings.findSkuNotMappedOrderIdsForListing(
                    channelAccountId, externalSkuId, cap + 1));
    int deferred = Math.max(0, orderIds.size() - cap);
    if (orderIds.size() > cap) {
      orderIds = orderIds.subList(0, cap);
    }
    return resolveOrders(tenantId, orderIds, deferred, "order.remap", null, false, false);
  }

  public int runScheduledBatch() {
    assertNoActiveTransaction("runScheduledBatch");
    UUID previousTenant = TenantContext.tenantId();
    UUID previousUser = TenantContext.userId();
    int processed = 0;
    long backoffOrders = 0;
    Instant now = clock.instant();
    try {
      TenantContext.clear();
      List<UUID> tenants = listings.listActiveTenantIds();
      for (UUID tenantId : tenants) {
        TenantContext.set(tenantId, null);
        try {
          List<UUID> orderIds =
              tenantReadTx.execute(
                  status ->
                      listings.findResolvableSkuNotMappedOrderIds(
                          tenantId, properties.getBatchSize(), now));
          ReevalSummary summary =
              resolveOrders(tenantId, orderIds, 0, "order.remap", null, false, true);
          processed += summary.released() + summary.outOfStock() + summary.stillHeld();
          backoffOrders +=
              tenantReadTx.execute(status -> retries.countInBackoff(tenantId, now));
        } catch (RuntimeException ex) {
          log.error("hold resolver failed for tenant {}", tenantId, ex);
        } finally {
          TenantContext.clear();
        }
      }
    } finally {
      backoffGauge.set(backoffOrders);
      restore(previousTenant, previousUser);
    }
    return processed;
  }

  public ReevalSummary resolveOrderRecheck(UUID orderId, String clientIdempotencyKey) {
    assertNoActiveTransaction("resolveOrderRecheck");
    UUID tenantId = TenantContext.requireTenantId();
    return resolveOrders(
        tenantId, List.of(orderId), 0, "order.recheck", clientIdempotencyKey, true, false);
  }

  private ReevalSummary resolveOrders(
      UUID tenantId,
      List<UUID> orderIds,
      int deferred,
      String keyPrefix,
      String recheckIdempotencyKey,
      boolean surfaceErrors,
      boolean applyBackoff) {
    int released = 0;
    int outOfStock = 0;
    int stillHeld = 0;
    for (UUID orderId : orderIds) {
      Outcome outcome =
          resolveOneWithRetries(
              tenantId, orderId, keyPrefix, recheckIdempotencyKey, surfaceErrors, applyBackoff);
      switch (outcome) {
        case RELEASED -> released++;
        case OUT_OF_STOCK -> outOfStock++;
        case STILL_HELD -> stillHeld++;
        case DEFERRED -> deferred++;
        default -> {}
      }
    }
    return new ReevalSummary(released, outOfStock, stillHeld, deferred);
  }

  private Outcome resolveOneWithRetries(
      UUID tenantId,
      UUID orderId,
      String keyPrefix,
      String recheckIdempotencyKey,
      boolean surfaceErrors,
      boolean applyBackoff) {
    int attempts = properties.getLockRetries();
    UUID attemptId = UUID.randomUUID();
    RuntimeException lastError = null;
    for (int attempt = 1; attempt <= attempts; attempt++) {
      try {
        Outcome outcome =
            tenantWriteTx.execute(
                status ->
                    resolver.resolveHeldOrder(
                        orderId, keyPrefix, recheckIdempotencyKey, attemptId));
        persistRetryState(tenantId, orderId, outcome, applyBackoff, null);
        return outcome;
      } catch (RuntimeException ex) {
        lastError = ex;
        if (OrderHoldResolver.retryableLock(ex) && attempt < attempts) {
          continue;
        }
        if (surfaceErrors) {
          throw ex;
        }
        log.warn(
            "hold resolver deferred orderId={} after {} attempts: {}",
            orderId,
            attempt,
            ex.getClass().getSimpleName());
        persistRetryState(tenantId, orderId, Outcome.DEFERRED, applyBackoff, ex);
        deferredCounter.increment();
        return Outcome.DEFERRED;
      }
    }
    persistRetryState(tenantId, orderId, Outcome.DEFERRED, applyBackoff, lastError);
    deferredCounter.increment();
    return Outcome.DEFERRED;
  }

  private void persistRetryState(
      UUID tenantId,
      UUID orderId,
      Outcome outcome,
      boolean applyBackoff,
      RuntimeException error) {
    try {
      tenantWriteTx.execute(
          status -> {
            if (outcome == Outcome.RELEASED || outcome == Outcome.OUT_OF_STOCK) {
              retries.clear(tenantId, orderId);
              return null;
            }
            if (!applyBackoff) {
              return null;
            }
            if (outcome == Outcome.DEFERRED || outcome == Outcome.STILL_HELD) {
              String code =
                  error == null
                      ? (outcome == Outcome.STILL_HELD ? "STILL_HELD" : "DEFERRED")
                      : error.getClass().getSimpleName();
              retries.recordBackoff(tenantId, orderId, code, clock.instant());
            }
            return null;
          });
    } catch (RuntimeException ex) {
      log.warn(
          "hold retry state update failed for tenantId={} orderId={}: {}",
          tenantId,
          orderId,
          ex.getClass().getSimpleName());
    }
  }

  private static void assertNoActiveTransaction(String operation) {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(operation + " must run outside a transaction");
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
