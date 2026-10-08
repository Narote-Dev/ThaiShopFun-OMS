package com.thaishopfun.oms.order.backfill;

import com.thaishopfun.oms.channel.Channel;
import com.thaishopfun.oms.channel.ChannelAccountRef;
import com.thaishopfun.oms.channel.ChannelAdapterRegistry;
import com.thaishopfun.oms.channel.api.OrderDetail;
import com.thaishopfun.oms.channel.api.PaymentStatus;
import com.thaishopfun.oms.inbox.InboxAggregateLock;
import com.thaishopfun.oms.order.ChannelAccountLookup;
import com.thaishopfun.oms.order.intake.OrderRestSnapshotApplier;
import com.thaishopfun.oms.order.intake.OrderRestSnapshotApplier.Outcome;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;
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
  private final InboxAggregateLock aggregateLock;
  private final JdbcTemplate jdbc;
  private final TransactionTemplate applyTx;
  private final Counter gapRefetches;

  public OrderGapRefetchService(
      ChannelAdapterRegistry adapters,
      ChannelAccountLookup channels,
      OrderRestSnapshotApplier applier,
      InboxAggregateLock aggregateLock,
      JdbcTemplate jdbc,
      PlatformTransactionManager transactions,
      MeterRegistry meters) {
    this.adapters = adapters;
    this.channels = channels;
    this.applier = applier;
    this.aggregateLock = aggregateLock;
    this.jdbc = jdbc;
    this.applyTx = new TransactionTemplate(transactions);
    this.applyTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    this.gapRefetches = Counter.builder(GAP_METRIC).register(meters);
  }

  @Override
  public void refetchAndApply(
      UUID tenantId,
      String shopId,
      String externalOrderId,
      long inboxAggregateVersion,
      String prefix) {
    assertNoActiveTransaction("refetchAndApply");
    ChannelAccountRef ref =
        channels
            .tsfByExternalShopId(shopId)
            .map(account -> new ChannelAccountRef(tenantId, account.id(), shopId))
            .orElseThrow(() -> new IllegalStateException("TSF account missing for shop"));
    var adapter = adapters.require(Channel.TSF);
    OrderDetail detail = adapter.getOrder(ref, externalOrderId);
    PaymentStatus payment = adapter.getPaymentStatus(ref, externalOrderId);
    Outcome outcome =
        applyTx.execute(
            status -> {
              aggregateLock.lockOrder(jdbc, tenantId, externalOrderId);
              return applier.apply(
                  tenantId, shopId, detail, payment, prefix, inboxAggregateVersion);
            });
    if (outcome == Outcome.APPLIED_NEEDS_PAID_CATCHUP) {
      outcome =
          applyTx.execute(
              status -> {
                aggregateLock.lockOrder(jdbc, tenantId, externalOrderId);
                return applier.applyPaidCatchUp(
                    tenantId, shopId, detail, payment, prefix, inboxAggregateVersion);
              });
    }
    if (outcome == Outcome.APPLIED) {
      gapRefetches.increment();
    }
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
}
