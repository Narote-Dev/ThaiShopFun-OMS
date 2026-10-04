package com.thaishopfun.oms.order.hold;

import com.thaishopfun.oms.order.ChannelAccountLookup.TsfAccount;
import com.thaishopfun.oms.order.OrderIntakeHooks;
import com.thaishopfun.oms.order.OrderLineRepository;
import com.thaishopfun.oms.order.OrderReservationCoverage;
import com.thaishopfun.oms.order.OrderStateException;
import com.thaishopfun.oms.order.OrderStateMachine;
import com.thaishopfun.oms.order.OrderStateMachine.GuardContext;
import com.thaishopfun.oms.order.OrderStateMachine.TransitionResult;
import com.thaishopfun.oms.order.OrderStockEnforcement;
import com.thaishopfun.oms.order.SalesOrder;
import com.thaishopfun.oms.order.SalesOrderRepository;
import com.thaishopfun.oms.order.ShadowDiffRepository;
import com.thaishopfun.oms.outbox.OutboxAppender;
import com.thaishopfun.oms.outbox.OutboxDraft;
import com.thaishopfun.oms.stock.ReserveDemandPlanner;
import com.thaishopfun.oms.stock.ReserveItem;
import com.thaishopfun.oms.stock.Shortfall;
import com.thaishopfun.oms.stock.StockOperationException;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

@Component
public class OrderHoldEffects {

  public static final String BUNDLE_WITHOUT_COMPONENTS_NOTE = "bundle has no components";
  public static final String BUSINESS_OVERSELL_METRIC = "oms.order.business_oversell";

  private final SalesOrderRepository orders;
  private final OrderLineRepository lines;
  private final OrderStateMachine stateMachine;
  private final OrderReservationCoverage coverage;
  private final ReserveDemandPlanner demandPlanner;
  private final ShadowDiffRepository shadowDiff;
  private final OutboxAppender outbox;
  private final OrderIntakeHooks hooks;
  private final JdbcTemplate jdbc;
  private final Clock clock;
  private final JsonMapper json;
  private final MeterRegistry meters;

  public OrderHoldEffects(
      SalesOrderRepository orders,
      OrderLineRepository lines,
      OrderStateMachine stateMachine,
      OrderReservationCoverage coverage,
      ReserveDemandPlanner demandPlanner,
      ShadowDiffRepository shadowDiff,
      OutboxAppender outbox,
      OrderIntakeHooks hooks,
      JdbcTemplate jdbc,
      Clock clock,
      JsonMapper json,
      MeterRegistry meters) {
    this.orders = orders;
    this.lines = lines;
    this.stateMachine = stateMachine;
    this.coverage = coverage;
    this.demandPlanner = demandPlanner;
    this.shadowDiff = shadowDiff;
    this.outbox = outbox;
    this.hooks = hooks;
    this.jdbc = jdbc;
    this.clock = clock;
    this.json = json;
    this.meters = meters;
  }

  public void maybeReadyToPick(
      SalesOrder order, TsfAccount account, List<ReserveItem> mappedItems, Instant now) {
    if (!"NONE".equals(order.holdReason())) {
      return;
    }
    GuardContext guard = guard(account, order, mappedItems);
    try {
      TransitionResult result =
          stateMachine.applyFulfillmentStatus(
              order, "READY_TO_PICK", "stock ready", "SYSTEM", guard);
      if (result.fulfillmentChanged()) {
        emitStatusChanged(result.order());
        hooks.afterOutbox();
      }
    } catch (OrderStateException ignored) {
      // Guards block READY_TO_PICK until payment/hold/reservation allow it.
    }
  }

  public SalesOrder applyHold(SalesOrder order, String holdReason, String holdNote) {
    return applyHold(order, holdReason, holdNote, "intake", "SYSTEM");
  }

  public SalesOrder applyHold(
      SalesOrder order, String holdReason, String holdNote, String reason, String actor) {
    TransitionResult hold =
        stateMachine.applyHoldReason(order, holdReason, holdNote, reason, actor);
    return hold.order();
  }

  public void recordOversell(
      TsfAccount account, List<HoldLine> mapped, List<Shortfall> shortfalls) {
    String mode = account.mode();
    boolean oversell =
        mapped.stream()
            .filter(HoldLine::mapped)
            .filter(line -> lineInShortfall(line, shortfalls))
            .anyMatch(
                line -> "ACTIVE".equals(mode) || ("CONTROL".equals(mode) && line.stockControl()));
    if (oversell) {
      meters.counter(BUSINESS_OVERSELL_METRIC, "mode", mode).increment();
    }
  }

  public String shadowDiffJson(List<HoldLine> mapped, List<Shortfall> shortfalls) {
    ArrayNode shortfallsNode = json.createArrayNode();
    for (Shortfall sf : shortfalls) {
      ObjectNode entry = json.createObjectNode();
      mapped.stream()
          .filter(
              line ->
                  line.skuId() != null
                      && (line.skuId().equals(sf.skuId())
                          || sf.requestedBy().contains(line.skuId())))
          .findFirst()
          .ifPresent(line -> entry.put("line_id", line.externalLineId()));
      entry.put("sku_id", sf.skuId().toString());
      entry.put("requested", sf.requested());
      entry.put("available", sf.available());
      shortfallsNode.add(entry);
    }
    ObjectNode root = json.createObjectNode();
    root.set("shortfalls", shortfallsNode);
    return json.writeValueAsString(root);
  }

  public String componentlessBundleShadowJson(List<ReserveItem> reserveItems) {
    Set<UUID> bundleSkus =
        demandPlanner.componentlessBundleSkus(
            reserveItems.stream().map(ReserveItem::skuId).toList());
    ArrayNode bundles = json.createArrayNode();
    for (UUID skuId : bundleSkus) {
      bundles.add(skuId.toString());
    }
    ObjectNode root = json.createObjectNode();
    root.put("reason", "BUNDLE_WITHOUT_COMPONENTS");
    root.set("bundle_skus", bundles);
    return json.writeValueAsString(root);
  }

  public String unknownSkuHoldNote(List<HoldLine> mapped, Set<UUID> missingSkuIds) {
    StringBuilder note = new StringBuilder("mapped sku not found");
    for (HoldLine line : mapped) {
      if (line.skuId() != null && missingSkuIds.contains(line.skuId())) {
        note.append(';').append(line.listingSkuId());
      }
    }
    return note.toString();
  }

  public String unknownSkuHoldNoteFromLines(UUID orderId, Set<UUID> missingSkuIds) {
    SalesOrder order = orders.findById(orderId).orElseThrow();
    List<HoldLine> mapped =
        lines.findByOrderId(orderId).stream()
            .map(
                line ->
                    new HoldLine(
                        line.id(),
                        line.externalLineId(),
                        line.skuId(),
                        line.qty(),
                        line.externalSkuId(),
                        listingStockControl(order.channelAccountId(), line.externalSkuId()),
                        line.skuId() != null))
            .toList();
    return unknownSkuHoldNote(mapped, missingSkuIds);
  }

  public Set<UUID> missingSkuIdsForUnknownSku(StockOperationException ex, List<ReserveItem> items) {
    UUID skuId = ex.skuId();
    if (skuId != null) {
      return Set.of(skuId);
    }
    return missingCatalogSkuIds(items.stream().map(ReserveItem::skuId).toList());
  }

  public static String shortfallNote(List<Shortfall> shortfalls) {
    StringBuilder note = new StringBuilder();
    for (Shortfall sf : shortfalls) {
      if (!note.isEmpty()) {
        note.append(';');
      }
      note.append(sf.skuId()).append(':').append(sf.requested()).append('/').append(sf.available());
    }
    return note.toString();
  }

  public boolean listingStockControl(UUID channelAccountId, String externalSkuId) {
    List<Boolean> flags =
        jdbc.query(
            """
            SELECT stock_control FROM channel_listing
            WHERE channel_account_id = ? AND external_sku_id = ? AND removed_at IS NULL
            """,
            (rs, row) -> rs.getBoolean("stock_control"),
            channelAccountId,
            externalSkuId);
    return !flags.isEmpty() && flags.get(0);
  }

  private GuardContext guard(TsfAccount account, SalesOrder order, List<ReserveItem> mappedItems) {
    boolean enforced = OrderStockEnforcement.enforced(account.mode(), account.status());
    boolean covers = !enforced || coverage.covers(order.id(), mappedItems, clock.instant());
    return new GuardContext(enforced, covers, clock.instant());
  }

  private void emitStatusChanged(SalesOrder order) {
    outbox.append(
        OutboxDraft.of(
            "order",
            order.externalOrderId(),
            "order.status_changed",
            Map.of(
                "order_id",
                order.externalOrderId(),
                "fulfillment_status",
                order.fulfillmentStatus()),
            order.version()));
  }

  private Set<UUID> missingCatalogSkuIds(List<UUID> skuIds) {
    if (skuIds.isEmpty()) {
      return Set.of();
    }
    Set<UUID> unique = new LinkedHashSet<>(skuIds);
    String placeholders = String.join(",", java.util.Collections.nCopies(unique.size(), "?"));
    List<UUID> found =
        jdbc.queryForList(
            "SELECT id FROM sku WHERE id IN (" + placeholders + ")", UUID.class, unique.toArray());
    Set<UUID> present = new LinkedHashSet<>(found);
    Set<UUID> missing = new LinkedHashSet<>();
    for (UUID id : unique) {
      if (!present.contains(id)) {
        missing.add(id);
      }
    }
    return missing;
  }

  private static boolean lineInShortfall(HoldLine line, List<Shortfall> shortfalls) {
    if (line.skuId() == null) {
      return false;
    }
    return shortfalls.stream()
        .anyMatch(sf -> sf.skuId().equals(line.skuId()) || sf.requestedBy().contains(line.skuId()));
  }

  public record HoldLine(
      UUID lineId,
      String externalLineId,
      UUID skuId,
      int qty,
      String listingSkuId,
      boolean stockControl,
      boolean mapped) {
    ReserveItem reserveItem() {
      return ReserveItem.of(skuId, qty);
    }
  }
}
