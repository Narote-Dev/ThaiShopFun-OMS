package com.thaishopfun.oms.order.hold;

import com.thaishopfun.oms.listing.ChannelListingRepository;
import com.thaishopfun.oms.order.hold.OrderHoldResolver.Outcome;
import com.thaishopfun.oms.tenant.TenantContext;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class OrderHoldResolverJob {

  private static final Logger log = LoggerFactory.getLogger(OrderHoldResolverJob.class);

  public record ReevalSummary(int released, int outOfStock, int stillHeld, int deferred) {

    public static ReevalSummary zero() {
      return new ReevalSummary(0, 0, 0, 0);
    }
  }

  private final OrderHoldResolver resolver;
  private final ChannelListingRepository listings;
  private final OrderHoldProperties properties;
  private final TransactionTemplate tenantTx;

  public OrderHoldResolverJob(
      OrderHoldResolver resolver,
      ChannelListingRepository listings,
      OrderHoldProperties properties,
      PlatformTransactionManager transactions) {
    this.resolver = resolver;
    this.listings = listings;
    this.properties = properties;
    this.tenantTx = new TransactionTemplate(transactions);
  }

  public ReevalSummary reevalAfterMapping(UUID channelAccountId, String externalSkuId) {
    return reevalForListing(channelAccountId, externalSkuId, properties.getReevalCap());
  }

  public ReevalSummary reevalForListing(
      UUID channelAccountId, String externalSkuId, int cap) {
    UUID tenantId = TenantContext.requireTenantId();
    List<UUID> orderIds =
        listings.findHeldOrderIdsForListing(channelAccountId, externalSkuId, cap + 1);
    int deferred = Math.max(0, orderIds.size() - cap);
    if (orderIds.size() > cap) {
      orderIds = orderIds.subList(0, cap);
    }
    return resolveOrders(tenantId, orderIds, deferred, "order.remap", null);
  }

  public int runScheduledBatch() {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException("hold resolver batch must run outside a transaction");
    }
    UUID previousTenant = TenantContext.tenantId();
    UUID previousUser = TenantContext.userId();
    int processed = 0;
    try {
      TenantContext.clear();
      List<UUID> tenants = listings.tenantsWithResolvableHolds(properties.getBatchSize());
      for (UUID tenantId : tenants) {
        TenantContext.set(tenantId, null);
        try {
          List<UUID> orderIds = listings.findHeldOrderIds(tenantId, properties.getBatchSize());
          ReevalSummary summary = resolveOrders(tenantId, orderIds, 0, "order.remap", null);
          processed += summary.released() + summary.outOfStock() + summary.stillHeld();
        } catch (RuntimeException ex) {
          log.error("hold resolver failed for tenant {}", tenantId, ex);
        } finally {
          TenantContext.clear();
        }
      }
    } finally {
      restore(previousTenant, previousUser);
    }
    return processed;
  }

  public ReevalSummary resolveOrderRecheck(UUID orderId, String clientIdempotencyKey) {
    UUID tenantId = TenantContext.requireTenantId();
    return resolveOrders(
        tenantId, List.of(orderId), 0, "order.recheck", clientIdempotencyKey);
  }

  private ReevalSummary resolveOrders(
      UUID tenantId,
      List<UUID> orderIds,
      int deferred,
      String keyPrefix,
      String recheckIdempotencyKey) {
    int released = 0;
    int outOfStock = 0;
    int stillHeld = 0;
    for (UUID orderId : orderIds) {
      Outcome outcome =
          resolveOneWithRetries(tenantId, orderId, keyPrefix, recheckIdempotencyKey);
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
      UUID tenantId, UUID orderId, String keyPrefix, String recheckIdempotencyKey) {
    int attempts = properties.getLockRetries();
    for (int attempt = 1; attempt <= attempts; attempt++) {
      try {
        return tenantTx.execute(
            status -> resolver.resolveHeldOrder(orderId, keyPrefix, recheckIdempotencyKey));
      } catch (RuntimeException ex) {
        if (OrderHoldResolver.retryableLock(ex) && attempt < attempts) {
          continue;
        }
        throw ex;
      }
    }
    return Outcome.STILL_HELD;
  }

  private static void restore(UUID tenantId, UUID userId) {
    if (tenantId == null) {
      TenantContext.clear();
    } else {
      TenantContext.set(tenantId, userId);
    }
  }
}
