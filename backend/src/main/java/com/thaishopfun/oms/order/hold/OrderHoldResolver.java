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
import com.thaishopfun.oms.stock.StockError;
import com.thaishopfun.oms.stock.StockOperationException;
import com.thaishopfun.oms.stock.StockOwner;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
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
   * Re-evaluates one held order inside the caller's transaction. {@code idempotencyPrefix} is {@code
   * order.remap} or {@code order.recheck}.
   */
  public Outcome resolveHeldOrder(UUID orderId, String idempotencyPrefix) {
    // Step 1: Load the order and skip work that does not apply.
    SalesOrder order = orders.findById(orderId).orElse(null);
    if (order == null || !"ACTIVE".equals(order.orderStatus())) {
      return Outcome.STILL_HELD;
    }
    if (!"SKU_NOT_MAPPED".equals(order.holdReason())
        && !"OUT_OF_STOCK".equals(order.holdReason())) {
      return Outcome.STILL_HELD;
    }
    Optional<TsfAccount> account = loadAccount(order.channelAccountId());
    if (account.isEmpty()) {
      return Outcome.STILL_HELD;
    }
    TsfAccount tsf = account.get();
    // Step 2: Same aggregate lock as inbox intake for this external order id.
    aggregateLock.lockOrder(jdbc, order.tenantId(), order.externalOrderId());
    order = orders.findById(orderId).orElseThrow();
    if (!"SKU_NOT_MAPPED".equals(order.holdReason())
        && !"OUT_OF_STOCK".equals(order.holdReason())) {
      return Outcome.STILL_HELD;
    }
    // Step 3: Refresh line sku_id from current channel_listing mappings.
    lines.updateSkuIdsFromListings(orderId, order.channelAccountId());
    List<HoldLine> holdLines = holdLines(order);
    boolean hasUnmapped = holdLines.stream().anyMatch(line -> !line.mapped());
    List<ReserveItem> reserveItems =
        holdLines.stream().filter(HoldLine::mapped).map(HoldLine::reserveItem).toList();
    boolean stockEnforced = OrderStockEnforcement.enforced(tsf.mode(), tsf.status());
    Instant now = clock.instant();
    // Step 4: Still-unmapped lines keep SKU_NOT_MAPPED.
    if (hasUnmapped) {
      if (!"SKU_NOT_MAPPED".equals(order.holdReason())) {
        order = effects.applyHold(order, "SKU_NOT_MAPPED", null);
      }
      return Outcome.STILL_HELD;
    }
    // Step 5: Componentless bundle is OUT_OF_STOCK without calling the engine.
    boolean componentlessBundle =
        stockEnforced
            && !reserveItems.isEmpty()
            && !demandPlanner
                .componentlessBundleSkus(reserveItems.stream().map(ReserveItem::skuId).toList())
                .isEmpty();
    if (componentlessBundle) {
      order = effects.applyHold(order, "OUT_OF_STOCK", OrderHoldEffects.BUNDLE_WITHOUT_COMPONENTS_NOTE);
      if ("SHADOW".equals(tsf.mode())) {
        shadowDiff.insertOrderDiff(
            tsf.id(),
            order.externalOrderId(),
            effects.componentlessBundleShadowJson(reserveItems),
            now);
      }
      return Outcome.OUT_OF_STOCK;
    }
    if (!stockEnforced || reserveItems.isEmpty()) {
      if (!"NONE".equals(order.holdReason())) {
        order = effects.applyHold(order, "NONE", null);
      }
      effects.maybeReadyToPick(orders.findById(orderId).orElseThrow(), tsf, reserveItems, now);
      return Outcome.RELEASED;
    }
    // Step 6: Try to ensure ORDER reservation with a stable idempotency key.
    String engineKey = engineKey(idempotencyPrefix, orderId, reserveItems);
    hooks.beforeEngineWrite();
    try {
      EnsureHoldResult held =
          engine.ensureOrderHold(
              StockOwner.order(orderId.toString()), reserveItems, engineKey);
      order = orders.findById(orderId).orElseThrow();
      if (held.held()) {
        if (!"NONE".equals(order.holdReason())) {
          order = effects.applyHold(order, "NONE", null);
        }
        effects.maybeReadyToPick(orders.findById(orderId).orElseThrow(), tsf, reserveItems, now);
        return Outcome.RELEASED;
      }
      List<Shortfall> shortfalls = held.shortfalls();
      effects.recordOversell(tsf, holdLines, shortfalls);
      if ("SHADOW".equals(tsf.mode())) {
        shadowDiff.insertOrderDiff(
            tsf.id(),
            order.externalOrderId(),
            effects.shadowDiffJson(holdLines, shortfalls),
            now);
      }
      order =
          effects.applyHold(order, "OUT_OF_STOCK", OrderHoldEffects.shortfallNote(shortfalls));
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
                  orderId, effects.missingSkuIdsForUnknownSku(ex, reserveItems)));
      return Outcome.STILL_HELD;
    }
  }

  static String engineKey(String prefix, UUID orderId, List<ReserveItem> items) {
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
    return prefix + ":" + orderId + ":" + hash;
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
                    rs.getObject("id", UUID.class),
                    rs.getString("mode"),
                    rs.getString("status")),
            channelAccountId);
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
  }

  static boolean retryableLock(RuntimeException ex) {
    return ex instanceof OrderOptimisticLockException;
  }
}
