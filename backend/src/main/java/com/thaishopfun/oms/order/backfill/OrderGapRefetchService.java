package com.thaishopfun.oms.order.backfill;

import com.thaishopfun.oms.channel.Channel;
import com.thaishopfun.oms.channel.ChannelAccountRef;
import com.thaishopfun.oms.channel.ChannelAdapterRegistry;
import com.thaishopfun.oms.channel.api.OrderDetail;
import com.thaishopfun.oms.channel.api.PaymentStatus;
import com.thaishopfun.oms.inbox.InboxAggregateLock;
import com.thaishopfun.oms.order.ChannelAccountLookup;
import com.thaishopfun.oms.order.SalesOrderRepository;
import com.thaishopfun.oms.order.intake.OrderRestSnapshotApplier;
import com.thaishopfun.oms.order.intake.OrderRestSnapshotApplier.Outcome;
import com.thaishopfun.oms.tenant.TenantContext;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

@Service
@org.springframework.context.annotation.Profile("!inbox-api-test")
public class OrderGapRefetchService implements OrderGapRefetch {

  public static final String GAP_METRIC = "oms.order.gap_refetch";

  private final ChannelAdapterRegistry adapters;
  private final ChannelAccountLookup channels;
  private final OrderRestSnapshotApplier applier;
  private final SalesOrderRepository orders;
  private final InboxAggregateLock aggregateLock;
  private final JdbcTemplate jdbc;
  private final TransactionTemplate tenantReadTx;
  private final TransactionTemplate applyTx;
  private final Counter gapRefetches;

  public OrderGapRefetchService(
      ChannelAdapterRegistry adapters,
      ChannelAccountLookup channels,
      OrderRestSnapshotApplier applier,
      SalesOrderRepository orders,
      InboxAggregateLock aggregateLock,
      JdbcTemplate jdbc,
      PlatformTransactionManager transactions,
      MeterRegistry meters) {
    this.adapters = adapters;
    this.channels = channels;
    this.applier = applier;
    this.orders = orders;
    this.aggregateLock = aggregateLock;
    this.jdbc = jdbc;
    this.tenantReadTx = new TransactionTemplate(transactions);
    this.tenantReadTx.setReadOnly(true);
    this.tenantReadTx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    this.applyTx = new TransactionTemplate(transactions);
    this.applyTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    this.gapRefetches = Counter.builder(GAP_METRIC).register(meters);
  }

  @Override
  public boolean refetchAndApply(
      UUID tenantId,
      String shopId,
      String externalOrderId,
      long inboxAggregateVersion,
      String prefix,
      String inboxEventType,
      JsonNode inboxPayload) {
    assertNoActiveTransaction("refetchAndApply");
    UUID previousTenant = TenantContext.tenantId();
    UUID previousUser = TenantContext.userId();
    try {
      TenantContext.set(tenantId, null);
      ChannelAccountRef ref =
          tenantReadTx.execute(
              status ->
                  channels
                      .tsfByExternalShopId(shopId)
                      .map(
                          account ->
                              new ChannelAccountRef(tenantId, account.id(), shopId))
                      .orElseThrow(
                          () -> new IllegalStateException("TSF account missing for shop")));
      var adapter = adapters.require(Channel.TSF);
      OrderDetail detail = adapter.getOrder(ref, externalOrderId);
      PaymentStatus payment = adapter.getPaymentStatus(ref, externalOrderId);
      long snapshotVersion = detail.aggregateVersion();
      if (inboxAggregateVersion > 0 && snapshotVersion < inboxAggregateVersion) {
        throw new GapSnapshotNotReadyException(snapshotVersion, inboxAggregateVersion);
      }
      Outcome outcome =
          applyTx.execute(
              status -> {
                aggregateLock.lockOrder(jdbc, tenantId, externalOrderId);
                return applier.apply(tenantId, shopId, detail, payment, prefix);
              });
      outcome = finalizeDeferred(tenantId, externalOrderId, shopId, detail, payment, prefix, outcome);
      if (outcome != Outcome.APPLIED && inboxPayload != null && !inboxPayload.isNull()) {
        Outcome inboxOutcome =
            applyTx.execute(
                status -> {
                  aggregateLock.lockOrder(jdbc, tenantId, externalOrderId);
                  return applier.applyGapInboxEvent(
                      tenantId, shopId, inboxEventType, inboxPayload, prefix);
                });
        if (inboxOutcome == Outcome.APPLIED) {
          outcome = Outcome.APPLIED;
        }
      }
      if (outcome == Outcome.APPLIED) {
        gapRefetches.increment();
        return true;
      }
      return false;
    } finally {
      restoreTenant(previousTenant, previousUser);
    }
  }

  private Outcome finalizeDeferred(
      UUID tenantId,
      String externalOrderId,
      String shopId,
      OrderDetail detail,
      PaymentStatus payment,
      String prefix,
      Outcome outcome) {
    if (outcome == Outcome.APPLIED_NEEDS_CANCEL_CATCHUP) {
      outcome =
          applyTx.execute(
              status -> {
                aggregateLock.lockOrder(jdbc, tenantId, externalOrderId);
                return applier.applyCancelCatchUp(tenantId, shopId, detail, prefix);
              });
    }
    if (outcome == Outcome.APPLIED_NEEDS_PAID_CATCHUP) {
      outcome =
          applyTx.execute(
              status -> {
                aggregateLock.lockOrder(jdbc, tenantId, externalOrderId);
                return applier.applyPaidCatchUp(tenantId, shopId, detail, payment, prefix);
              });
    }
    return outcome;
  }

  public static String shopId(JsonNode payload) {
    return payload.path("tsf_shop_id").asString(null);
  }

  public static String orderId(JsonNode payload) {
    return payload.path("data").path("order_id").asString(null);
  }

  private static void assertNoActiveTransaction(String operation) {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(operation + " must run outside a transaction");
    }
  }

  private static void restoreTenant(UUID tenantId, UUID userId) {
    if (tenantId == null) {
      TenantContext.clear();
    } else {
      TenantContext.set(tenantId, userId);
    }
  }
}
