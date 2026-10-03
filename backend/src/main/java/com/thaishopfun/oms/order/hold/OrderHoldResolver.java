package com.thaishopfun.oms.order.hold;

import com.thaishopfun.oms.inbox.InboxAggregateLock;
import com.thaishopfun.oms.order.ChannelAccountLookup;
import com.thaishopfun.oms.order.ChannelAccountLookup.TsfAccount;
import com.thaishopfun.oms.order.OrderIntakeHooks;
import com.thaishopfun.oms.order.OrderLineRepository;
import com.thaishopfun.oms.order.OrderOptimisticLockException;
import com.thaishopfun.oms.order.OrderStockEnforcement;
import com.thaishopfun.oms.order.SalesOrder;
import com.thaishopfun.oms.order.SalesOrderRepository;
import com.thaishopfun.oms.order.ShadowDiffRepository;
import com.thaishopfun.oms.order.hold.OrderHoldEffects.HoldLine;
import com.thaishopfun.oms.stock.EnsureHoldResult;
import com.thaishopfun.oms.stock.ReservationEngine;
import com.thaishopfun.oms.stock.ReserveDemandPlanner;
import com.thaishopfun.oms.stock.ReserveItem;
import com.thaishopfun.oms.stock.Shortfall;
import com.thaishopfun.oms.stock.StockBusyException;
import com.thaishopfun.oms.stock.StockConflictException;
import com.thaishopfun.oms.stock.StockError;
import com.thaishopfun.oms.stock.StockOperationException;
import com.thaishopfun.oms.stock.StockOwner;
import com.thaishopfun.oms.stock.StockRetry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class OrderHoldResolver {

  public enum Outcome {
    RELEASED,
    OUT_OF_STOCK,
    STILL_HELD,
    DEFERRED
  }

  private final SalesOrderRepository orders;
  private final OrderLineRepository lines;
  private final ChannelAccountLookup channels;
  private final OrderHoldEffects effects;
  private final ReserveDemandPlanner demandPlanner;
  private final ReservationEngine engine;
  private final ShadowDiffRepository shadowDiff;
  private final OrderIntakeHooks hooks;
  private final InboxAggregateLock aggregateLock;
  private final JdbcTemplate jdbc;
  private final Clock clock;

  public OrderHoldResolver(
      SalesOrderRepository orders,
      OrderLineRepository lines,
      ChannelAccountLookup channels,
      OrderHoldEffects effects,
      ReserveDemandPlanner demandPlanner,
      ReservationEngine engine,
      ShadowDiffRepository shadowDiff,
      OrderIntakeHooks hooks,
      InboxAggregateLock aggregateLock,
      JdbcTemplate jdbc,
      Clock clock) {
    this.orders = orders;
    this.lines = lines;
    this.channels = channels;
    this.effects = effects;
    this.demandPlanner = demandPlanner;
    this.engine = engine;
    this.shadowDiff = shadowDiff;
    this.hooks = hooks;
    this.aggregateLock = aggregateLock;
    this.jdbc = jdbc;
    this.clock = clock;
  }

  /**
   * Re-evaluates one held order inside the caller's transaction. {@code idempotencyPrefix} is
   * {@code order.remap} or {@code order.recheck}.
   */
  public Outcome resolveHeldOrder(
      UUID orderId, String idempotencyPrefix, String recheckIdempotencyKey, UUID attemptId) {
    boolean manualRecheck = "order.recheck".equals(idempotencyPrefix);
    String historyReason = manualRecheck ? "hold recheck" : "sku mapped";
    String historyActor = manualRecheck ? "USER" : "SYSTEM";

    SalesOrder order = orders.findById(orderId).orElse(null);
    if (order == null || !"ACTIVE".equals(order.orderStatus())) {
      return Outcome.STILL_HELD;
    }
    if (!"UNFULFILLED".equals(order.fulfillmentStatus())) {
      return Outcome.STILL_HELD;
    }
    if (!manualRecheck && !"SKU_NOT_MAPPED".equals(order.holdReason())) {
      return Outcome.STILL_HELD;
    }
    if (manualRecheck
        && !"SKU_NOT_MAPPED".equals(order.holdReason())
        && !"OUT_OF_STOCK".equals(order.holdReason())) {
      return Outcome.STILL_HELD;
    }
    Optional<TsfAccount> account = loadAccount(order.channelAccountId());
    if (account.isEmpty()) {
      return Outcome.STILL_HELD;
    }
    TsfAccount tsf = account.get();
    aggregateLock.lockOrder(jdbc, order.tenantId(), order.externalOrderId());
    order = orders.findById(orderId).orElseThrow();
    if (!"ACTIVE".equals(order.orderStatus()) || !"UNFULFILLED".equals(order.fulfillmentStatus())) {
      return Outcome.STILL_HELD;
    }
    if ("CHANNEL_CANCEL_PENDING".equals(order.holdReason())) {
      return Outcome.STILL_HELD;
    }
    if (!manualRecheck && !"SKU_NOT_MAPPED".equals(order.holdReason())) {
      return Outcome.STILL_HELD;
    }
    if (manualRecheck
        && !"SKU_NOT_MAPPED".equals(order.holdReason())
        && !"OUT_OF_STOCK".equals(order.holdReason())) {
      return Outcome.STILL_HELD;
    }

    boolean wasOutOfStock = "OUT_OF_STOCK".equals(order.holdReason());
    lines.updateSkuIdsFromListings(orderId, order.channelAccountId());
    List<HoldLine> holdLines = holdLines(order);
    boolean hasUnmapped = holdLines.stream().anyMatch(line -> !line.mapped());
    List<ReserveItem> reserveItems =
        holdLines.stream().filter(HoldLine::mapped).map(HoldLine::reserveItem).toList();
    boolean stockEnforced = OrderStockEnforcement.enforced(tsf.mode(), tsf.status());
    Instant now = clock.instant();
    if (hasUnmapped) {
      if (!"SKU_NOT_MAPPED".equals(order.holdReason())) {
        order = effects.applyHold(order, "SKU_NOT_MAPPED", null, historyReason, historyActor);
      }
      return Outcome.STILL_HELD;
    }
    boolean componentlessBundle =
        stockEnforced
            && !reserveItems.isEmpty()
            && !demandPlanner
                .componentlessBundleSkus(reserveItems.stream().map(ReserveItem::skuId).toList())
                .isEmpty();
    if (componentlessBundle) {
      if (!wasOutOfStock) {
        order =
            effects.applyHold(
                order,
                "OUT_OF_STOCK",
                OrderHoldEffects.BUNDLE_WITHOUT_COMPONENTS_NOTE,
                historyReason,
                historyActor);
        if ("SHADOW".equals(tsf.mode())) {
          shadowDiff.insertOrderDiff(
              tsf.id(),
              order.externalOrderId(),
              effects.componentlessBundleShadowJson(reserveItems),
              now);
        }
      }
      return Outcome.OUT_OF_STOCK;
    }
    if (!stockEnforced || reserveItems.isEmpty()) {
      if (!"NONE".equals(order.holdReason())) {
        order = effects.applyHold(order, "NONE", null, historyReason, historyActor);
      }
      effects.maybeReadyToPick(orders.findById(orderId).orElseThrow(), tsf, reserveItems, now);
      return Outcome.RELEASED;
    }
    String engineKey =
        manualRecheck && recheckIdempotencyKey != null
            ? "order.recheck:" + orderId + ":" + recheckIdempotencyKey
            : engineKey(idempotencyPrefix, orderId, reserveItems, attemptId);
    hooks.beforeEngineWrite();
    try {
      EnsureHoldResult held =
          engine.ensureOrderHold(StockOwner.order(orderId.toString()), reserveItems, engineKey);
      order = orders.findById(orderId).orElseThrow();
      if (held.held()) {
        if (!"NONE".equals(order.holdReason())) {
          order = effects.applyHold(order, "NONE", null, historyReason, historyActor);
        }
        effects.maybeReadyToPick(orders.findById(orderId).orElseThrow(), tsf, reserveItems, now);
        return Outcome.RELEASED;
      }
      List<Shortfall> shortfalls = held.shortfalls();
      if (!wasOutOfStock) {
        effects.recordOversell(tsf, holdLines, shortfalls);
        if ("SHADOW".equals(tsf.mode())) {
          shadowDiff.insertOrderDiff(
              tsf.id(),
              order.externalOrderId(),
              effects.shadowDiffJson(holdLines, shortfalls),
              now);
        }
      }
      order =
          effects.applyHold(
              order,
              "OUT_OF_STOCK",
              OrderHoldEffects.shortfallNote(shortfalls),
              historyReason,
              historyActor);
      return Outcome.OUT_OF_STOCK;
    } catch (StockOperationException ex) {
      if (ex.error() != StockError.UNKNOWN_SKU) {
        throw ex;
      }
      order =
          effects.applyHold(
              order,
              "SKU_NOT_MAPPED",
              effects.unknownSkuHoldNoteFromLines(
                  orderId, effects.missingSkuIdsForUnknownSku(ex, reserveItems)),
              historyReason,
              historyActor);
      return Outcome.STILL_HELD;
    }
  }

  static String engineKey(String prefix, UUID orderId, List<ReserveItem> items, UUID attemptId) {
    List<ReserveItem> sorted = new ArrayList<>(items);
    sorted.sort(
        Comparator.comparing((ReserveItem item) -> item.skuId().toString())
            .thenComparingInt(ReserveItem::qty));
    StringBuilder canonical = new StringBuilder();
    for (ReserveItem item : sorted) {
      if (!canonical.isEmpty()) {
        canonical.append(',');
      }
      canonical.append(item.skuId()).append(':').append(item.qty());
    }
    String hash = sha256(canonical.toString());
    return prefix + ":" + orderId + ":" + attemptId + ":" + hash;
  }

  private static String sha256(String canonical) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 is not available", ex);
    }
  }

  private List<HoldLine> holdLines(SalesOrder order) {
    return lines.findByOrderId(order.id()).stream()
        .map(
            line ->
                new HoldLine(
                    line.id(),
                    line.externalLineId(),
                    line.skuId(),
                    line.qty(),
                    line.externalSkuId(),
                    effects.listingStockControl(order.channelAccountId(), line.externalSkuId()),
                    line.skuId() != null))
        .toList();
  }

  private Optional<TsfAccount> loadAccount(UUID channelAccountId) {
    List<TsfAccount> rows =
        jdbc.query(
            """
            SELECT id, mode, status FROM channel_account WHERE id = ?
            """,
            (rs, row) ->
                new TsfAccount(
                    rs.getObject("id", UUID.class), rs.getString("mode"), rs.getString("status")),
            channelAccountId);
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
  }

  static boolean retryableLock(RuntimeException ex) {
    if (ex instanceof OrderOptimisticLockException
        || ex instanceof StockConflictException
        || ex instanceof StockBusyException) {
      return true;
    }
    return StockRetry.classify(ex) != null;
  }
}
