package com.thaishopfun.oms.order.backfill;

import com.thaishopfun.oms.channel.Channel;
import com.thaishopfun.oms.channel.ChannelAccountRef;
import com.thaishopfun.oms.channel.ChannelAdapter;
import com.thaishopfun.oms.channel.ChannelAdapterRegistry;
import com.thaishopfun.oms.channel.api.OrderDetail;
import com.thaishopfun.oms.channel.api.OrderPage;
import com.thaishopfun.oms.channel.api.OrderSummary;
import com.thaishopfun.oms.channel.api.PaymentStatus;
import com.thaishopfun.oms.inbox.InboxAggregateLock;
import com.thaishopfun.oms.order.SalesOrder;
import com.thaishopfun.oms.order.SalesOrderRepository;
import com.thaishopfun.oms.order.intake.OrderRestSnapshotApplier;
import com.thaishopfun.oms.order.intake.OrderRestSnapshotApplier.Outcome;
import com.thaishopfun.oms.tenant.TenantContext;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class OrderBackfillJob {

  public static final String FETCHED = "oms.order.backfill.fetched";
  public static final String APPLIED = "oms.order.backfill.applied";
  public static final String SKIPPED = "oms.order.backfill.skipped";
  public static final String FAILED = "oms.order.backfill.failed";
  public static final String LAG = "oms.order.backfill.lag_seconds";

  private static final Logger log = LoggerFactory.getLogger(OrderBackfillJob.class);

  private final OrderBackfillProperties properties;
  private final ChannelAdapterRegistry adapters;
  private final OrderSyncCursorRepository cursors;
  private final OrderRestSnapshotApplier applier;
  private final SalesOrderRepository orders;
  private final InboxAggregateLock aggregateLock;
  private final JdbcTemplate jdbc;
  private final TransactionTemplate tenantReadTx;
  private final TransactionTemplate tenantWriteTx;
  private final Clock clock;
  private final Counter fetched;
  private final Counter applied;
  private final Counter skipped;
  private final Counter failed;

  public OrderBackfillJob(
      OrderBackfillProperties properties,
      ChannelAdapterRegistry adapters,
      OrderSyncCursorRepository cursors,
      OrderRestSnapshotApplier applier,
      SalesOrderRepository orders,
      InboxAggregateLock aggregateLock,
      JdbcTemplate jdbc,
      PlatformTransactionManager transactions,
      Clock clock,
      MeterRegistry meters) {
    this.properties = properties;
    this.adapters = adapters;
    this.cursors = cursors;
    this.applier = applier;
    this.orders = orders;
    this.aggregateLock = aggregateLock;
    this.jdbc = jdbc;
    this.tenantReadTx = new TransactionTemplate(transactions);
    this.tenantReadTx.setReadOnly(true);
    this.tenantReadTx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    this.tenantWriteTx = new TransactionTemplate(transactions);
    this.tenantWriteTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    this.tenantWriteTx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    this.clock = clock;
    this.fetched = Counter.builder(FETCHED).register(meters);
    this.applied = Counter.builder(APPLIED).register(meters);
    this.skipped = Counter.builder(SKIPPED).register(meters);
    this.failed = Counter.builder(FAILED).register(meters);
  }

  public int runOnce() {
    return runOnceForTenants(listTenants());
  }

  /** Test hook: backfill one tenant without scanning every eligible shop in the database. */
  public int runOnceForTenant(UUID tenantId) {
    return runOnceForTenants(List.of(tenantId));
  }

  private int runOnceForTenants(List<UUID> tenants) {
    assertNoActiveTransaction("runOnce");
    UUID previousTenant = TenantContext.tenantId();
    UUID previousUser = TenantContext.userId();
    int touched = 0;
    try {
      TenantContext.clear();
      for (UUID tenantId : tenants) {
        TenantContext.set(tenantId, null);
        try {
          touched += runTenant(tenantId);
        } catch (RuntimeException ex) {
          log.error("order backfill failed for tenant {}", tenantId, ex);
        } finally {
          TenantContext.clear();
        }
      }
    } finally {
      restore(previousTenant, previousUser);
    }
    return touched;
  }

  private int runTenant(UUID tenantId) {
    List<AccountRow> accounts =
        tenantReadTx.execute(
            status ->
                jdbc.query(
                    """
                    SELECT id, external_shop_id, channel, status
                    FROM channel_account
                    WHERE tenant_id = ? AND channel = 'TSF' AND status <> 'DISCONNECTED'
                    """,
                    (rs, row) ->
                        new AccountRow(
                            rs.getObject("id", UUID.class),
                            rs.getString("external_shop_id"),
                            rs.getString("channel"),
                            rs.getString("status")),
                    tenantId));
    if (accounts == null || accounts.isEmpty()) {
      return 0;
    }
    int touched = 0;
    for (AccountRow account : accounts) {
      touched += runAccount(tenantId, account);
    }
    return touched;
  }

  private int runAccount(UUID tenantId, AccountRow account) {
    ChannelAdapter adapter = adapters.optional(Channel.valueOf(account.channel()));
    if (adapter == null || !adapter.capabilities().supportsOrderPull()) {
      return 0;
    }
    ChannelAccountRef ref = new ChannelAccountRef(tenantId, account.id(), account.externalShopId());
    OrderSyncCursorRepository.State state =
        cursors
            .load(tenantId, account.id())
            .orElse(new OrderSyncCursorRepository.State(Instant.EPOCH, null));
    Instant watermark = state.updatedSince() == null ? Instant.EPOCH : state.updatedSince();
    Instant since = watermark.minus(properties.getOverlap());
    String pageCursor = state.pageCursor();
    Instant runStart = clock.instant();
    int touched = 0;
    try {
      while (true) {
        assertNoActiveTransaction("listOrders");
        OrderPage page = adapter.listOrders(ref, since, pageCursor, properties.getPageLimit());
        for (OrderSummary summary : page.orders()) {
          fetched.increment();
          if (needsApply(account.id(), summary)) {
            touched +=
                applyOne(tenantId, ref, account.externalShopId(), adapter, summary.orderId());
          } else {
            skipped.increment();
          }
        }
        pageCursor = page.nextCursor();
        String savedCursor = pageCursor;
        tenantWriteTx.executeWithoutResult(
            status -> cursors.saveProgress(tenantId, account.id(), watermark, savedCursor));
        if (pageCursor == null || pageCursor.isBlank()) {
          break;
        }
      }
      tenantWriteTx.executeWithoutResult(
          status -> cursors.commitSuccess(tenantId, account.id(), runStart));
      metersLag(watermark, runStart);
    } catch (RuntimeException ex) {
      failed.increment();
      log.warn("order backfill account {} failed; cursor retained", account.id(), ex);
      throw ex;
    }
    return touched;
  }

  private boolean needsApply(UUID channelAccountId, OrderSummary summary) {
    Optional<SalesOrder> existing =
        tenantReadTx.execute(
            status -> orders.findByExternalId(channelAccountId, summary.orderId()));
    if (existing.isEmpty()) {
      return true;
    }
    long known = existing.get().externalVersion() == null ? 0L : existing.get().externalVersion();
    return summary.aggregateVersion() > known;
  }

  private int applyOne(
      UUID tenantId, ChannelAccountRef ref, String shopId, ChannelAdapter adapter, String orderId) {
    assertNoActiveTransaction("getOrder");
    OrderDetail detail = adapter.getOrder(ref, orderId);
    PaymentStatus payment = adapter.getPaymentStatus(ref, orderId);
    Outcome outcome =
        tenantWriteTx.execute(
            status -> {
              aggregateLock.lockOrder(jdbc, tenantId, orderId);
              return applier.apply(tenantId, shopId, detail, payment, "backfill:" + orderId, 0);
            });
    if (outcome == Outcome.APPLIED) {
      applied.increment();
      return 1;
    }
    skipped.increment();
    return 0;
  }

  private void metersLag(Instant watermark, Instant now) {
    if (watermark == null) {
      return;
    }
    long seconds = Math.max(0, now.getEpochSecond() - watermark.getEpochSecond());
    log.debug("order backfill {} lag_seconds={}", LAG, seconds);
  }

  private List<UUID> listTenants() {
    return jdbc.query(
        "SELECT id FROM list_tenants_for_order_backfill()",
        (rs, row) -> rs.getObject("id", UUID.class));
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

  private record AccountRow(UUID id, String externalShopId, String channel, String status) {}
}
