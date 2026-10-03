package com.thaishopfun.oms.order.intake;

import com.thaishopfun.oms.auth.UuidV7;
import com.thaishopfun.oms.inbox.InboxDeferException;
import com.thaishopfun.oms.inbox.InboxMessage;
import com.thaishopfun.oms.inbox.InboxProperties;
import com.thaishopfun.oms.inbox.NonRetryableInboxException;
import com.thaishopfun.oms.order.ChannelAccountLookup;
import com.thaishopfun.oms.order.ChannelAccountLookup.TsfAccount;
import com.thaishopfun.oms.order.OrderIntakeHooks;
import com.thaishopfun.oms.order.OrderLineRepository;
import com.thaishopfun.oms.order.OrderProperties;
import com.thaishopfun.oms.order.OrderRecipientRepository;
import com.thaishopfun.oms.order.OrderReservationCoverage;
import com.thaishopfun.oms.order.OrderStateException;
import com.thaishopfun.oms.order.OrderStateMachine;
import com.thaishopfun.oms.order.OrderStateMachine.GuardContext;
import com.thaishopfun.oms.order.OrderStateMachine.TransitionResult;
import com.thaishopfun.oms.order.OrderStockEnforcement;
import com.thaishopfun.oms.order.Recipient;
import com.thaishopfun.oms.order.ReconciliationIssueRepository;
import com.thaishopfun.oms.order.SalesOrder;
import com.thaishopfun.oms.order.SalesOrderRepository;
import com.thaishopfun.oms.order.ShadowDiffRepository;
import com.thaishopfun.oms.outbox.OutboxAppender;
import com.thaishopfun.oms.outbox.OutboxDraft;
import com.thaishopfun.oms.stock.AdoptResult;
import com.thaishopfun.oms.stock.EnsureHoldResult;
import com.thaishopfun.oms.stock.ReservationEngine;
import com.thaishopfun.oms.stock.ReserveDemandPlanner;
import com.thaishopfun.oms.stock.ReserveItem;
import com.thaishopfun.oms.stock.Shortfall;
import com.thaishopfun.oms.stock.StockError;
import com.thaishopfun.oms.stock.StockOperationException;
import com.thaishopfun.oms.stock.StockOwner;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

@Service
public class OrderIntakeSupport {

  private static final Logger log = LoggerFactory.getLogger(OrderIntakeSupport.class);

  static final String TSF_CHANNEL_ACCOUNT_MISSING = "TSF_CHANNEL_ACCOUNT_MISSING";
  public static final String BUSINESS_OVERSELL_METRIC = "oms.order.business_oversell";
  static final String BUNDLE_WITHOUT_COMPONENTS_NOTE = "bundle has no components";

  private final ChannelAccountLookup channels;
  private final SalesOrderRepository orders;
  private final OrderLineRepository lines;
  private final OrderRecipientRepository recipients;
  private final OrderStateMachine stateMachine;
  private final OrderReservationCoverage coverage;
  private final ReserveDemandPlanner demandPlanner;
  private final ReservationEngine engine;
  private final ReconciliationIssueRepository reconciliation;
  private final ShadowDiffRepository shadowDiff;
  private final OutboxAppender outbox;
  private final OrderIntakeHooks hooks;
  private final OrderProperties orderProperties;
  private final InboxProperties inboxProperties;
  private final JdbcTemplate jdbc;
  private final Clock clock;
  private final JsonMapper json;
  private final MeterRegistry meters;

  public OrderIntakeSupport(
      ChannelAccountLookup channels,
      SalesOrderRepository orders,
      OrderLineRepository lines,
      OrderRecipientRepository recipients,
      OrderStateMachine stateMachine,
      OrderReservationCoverage coverage,
      ReserveDemandPlanner demandPlanner,
      ReservationEngine engine,
      ReconciliationIssueRepository reconciliation,
      ShadowDiffRepository shadowDiff,
      OutboxAppender outbox,
      OrderIntakeHooks hooks,
      OrderProperties orderProperties,
      InboxProperties inboxProperties,
      JdbcTemplate jdbc,
      Clock clock,
      JsonMapper json,
      MeterRegistry meters) {
    this.channels = channels;
    this.orders = orders;
    this.lines = lines;
    this.recipients = recipients;
    this.stateMachine = stateMachine;
    this.coverage = coverage;
    this.demandPlanner = demandPlanner;
    this.engine = engine;
    this.reconciliation = reconciliation;
    this.shadowDiff = shadowDiff;
    this.outbox = outbox;
    this.hooks = hooks;
    this.orderProperties = orderProperties;
    this.inboxProperties = inboxProperties;
    this.jdbc = jdbc;
    this.clock = clock;
    this.json = json;
    this.meters = meters;
  }

  TsfAccount requireTsfAccount(InboxMessage message) {
    String shopId = text(message.payload(), "tsf_shop_id");
    Optional<TsfAccount> account = channels.tsfByExternalShopId(shopId);
    if (account.isEmpty()) {
      throw new RuntimeException(TSF_CHANNEL_ACCOUNT_MISSING);
    }
    return account.get();
  }

  SalesOrder requireOrder(TsfAccount account, String externalOrderId) {
    return orders
        .findByExternalId(account.id(), externalOrderId)
        .orElseThrow(() -> new InboxDeferException(inboxProperties.getDeferDelay()));
  }

  void handleCreated(InboxMessage message) {
    TsfAccount account = requireTsfAccount(message);
    JsonNode data = message.payload().path("data");
    CreatedPayload payload = CreatedPayload.parse(data, clock);
    if (orders.existsByExternalId(account.id(), payload.orderId())) {
      return;
    }
    UUID orderId = UuidV7.generate();
    Instant now = clock.instant();
    String paymentStatus = "COD".equals(payload.paymentMethod()) ? "COD_PENDING" : "PENDING";
    SalesOrder order =
        new SalesOrder(
            orderId,
            message.tenantId(),
            account.id(),
            payload.orderId(),
            "ACTIVE",
            paymentStatus,
            "UNFULFILLED",
            "NONE",
            null,
            null,
            payload.paymentMethod(),
            payload.currency(),
            payload.subtotal(),
            payload.shippingFee(),
            payload.discount(),
            payload.grandTotal(),
            orderedAt(message, payload),
            null,
            payload.shipBy(),
            message.aggregateVersion(),
            0);
    orders.insert(order);
    recipients.insert(orderId, payload.recipient(), payload.redactAfter());
    List<LineMapping> mapped = insertLines(orderId, account.id(), payload.lines());

    boolean stockEnforced = stockEnforced(account);
    Instant holdExpires = null;
    if ("PREPAID".equals(payload.paymentMethod()) && payload.paymentExpiresAt() != null) {
      holdExpires = payload.paymentExpiresAt().plus(orderProperties.getUnpaidHoldGrace());
    }

    List<ReserveItem> reserveItems =
        mapped.stream().filter(LineMapping::mapped).map(LineMapping::reserveItem).toList();
    boolean hasUnmapped = mapped.stream().anyMatch(line -> !line.mapped());
    boolean componentlessBundle =
        stockEnforced
            && !reserveItems.isEmpty()
            && !demandPlanner
                .componentlessBundleSkus(reserveItems.stream().map(ReserveItem::skuId).toList())
                .isEmpty();
    List<Shortfall> stockShortfalls = List.of();
    boolean unknownSkuAtAdopt = false;
    String unknownSkuNote = null;
    if (stockEnforced && !reserveItems.isEmpty() && !componentlessBundle) {
      UUID groupId = parseUuid(payload.reservationId());
      hooks.beforeEngineWrite();
      try {
        AdoptResult adopt =
            engine.adoptForOrder(
                groupId,
                StockOwner.order(orderId.toString()),
                reserveItems,
                holdExpires,
                "order.adopt:" + message.eventId());
        if (!adopt.adopted()) {
          stockShortfalls = adopt.shortfalls();
        }
      } catch (StockOperationException ex) {
        if (ex.error() != StockError.UNKNOWN_SKU) {
          throw ex;
        }
        unknownSkuAtAdopt = true;
        unknownSkuNote = unknownSkuHoldNote(mapped, reserveItems);
      }
    }

    String holdReason = "NONE";
    String holdNote = null;
    if (unknownSkuAtAdopt) {
      holdReason = "SKU_NOT_MAPPED";
      holdNote = unknownSkuNote;
    } else if (hasUnmapped) {
      holdReason = "SKU_NOT_MAPPED";
      if (!stockShortfalls.isEmpty()) {
        holdNote = shortfallNote(stockShortfalls);
      }
    } else if (componentlessBundle) {
      holdReason = "OUT_OF_STOCK";
      holdNote = BUNDLE_WITHOUT_COMPONENTS_NOTE;
      if ("SHADOW".equals(account.mode())) {
        shadowDiff.insertOrderDiff(
            account.id(), payload.orderId(), componentlessBundleShadowJson(reserveItems), now);
      }
    } else if (!stockShortfalls.isEmpty()) {
      holdReason = "OUT_OF_STOCK";
      recordOversell(account, mapped, stockShortfalls);
      if ("SHADOW".equals(account.mode())) {
        shadowDiff.insertOrderDiff(
            account.id(), payload.orderId(), shadowDiffJson(mapped, stockShortfalls), now);
      }
    }

    SalesOrder current = orders.findById(orderId).orElseThrow();
    if (!"NONE".equals(holdReason)) {
      current = applyHold(current, holdReason, holdNote);
    }
    maybeReadyToPick(orders.findById(orderId).orElseThrow(), account, reserveItems, now);
  }

  void handlePaid(InboxMessage message) {
    TsfAccount account = requireTsfAccount(message);
    JsonNode data = message.payload().path("data");
    String orderId = requiredText(data, "order_id");
    SalesOrder order = requireOrder(account, orderId);
    if ("CANCELLED".equals(order.orderStatus())) {
      reconciliation.upsertOpen(message.id(), "PAID_AFTER_CANCEL", order.id(), "{}");
      return;
    }
    if ("PAID".equals(order.paymentStatus())) {
      return;
    }
    Instant paidAt = eventOccurredAt(message);
    order = applyPayment(order, "PAID", paidAt, account);
    List<ReserveItem> items = mappedReserveItems(order.id());
    boolean componentlessBundle =
        stockEnforced(account)
            && !items.isEmpty()
            && !demandPlanner
                .componentlessBundleSkus(items.stream().map(ReserveItem::skuId).toList())
                .isEmpty();
    if (stockEnforced(account) && !items.isEmpty() && !componentlessBundle) {
      hooks.beforeEngineWrite();
      try {
        EnsureHoldResult held =
            engine.ensureOrderHold(
                StockOwner.order(order.id().toString()),
                items,
                "order.ensure:" + message.eventId());
        if (held.held()) {
          order = orders.findById(order.id()).orElseThrow();
          if ("OUT_OF_STOCK".equals(order.holdReason())) {
            order = applyHold(order, "NONE", null);
          }
        } else {
          order = applyHold(order, "OUT_OF_STOCK", shortfallNote(held.shortfalls()));
        }
      } catch (StockOperationException ex) {
        if (ex.error() != StockError.UNKNOWN_SKU) {
          throw ex;
        }
        order = applyHold(order, "SKU_NOT_MAPPED", unknownSkuHoldNoteFromLines(order.id(), items));
      }
    }
    maybeReadyToPick(orders.findById(order.id()).orElseThrow(), account, items, paidAt);
  }

  void handleCancelled(InboxMessage message) {
    TsfAccount account = requireTsfAccount(message);
    String orderId = requiredText(message.payload().path("data"), "order_id");
    SalesOrder order = requireOrder(account, orderId);
    if ("SHIPPED".equals(order.fulfillmentStatus())
        || "DELIVERED".equals(order.fulfillmentStatus())) {
      reconciliation.upsertOpen(message.id(), "CANCEL_AFTER_SHIPPED", order.id(), "{}");
      return;
    }
    if ("CANCELLED".equals(order.orderStatus())) {
      return;
    }
    hooks.beforeEngineWrite();
    engine.release(StockOwner.order(order.id().toString()), "order.release:" + message.eventId());
    stateMachine.applyOrderStatus(
        order, "CANCELLED", "TSF cancel", "TSF", guard(account, order, List.of()));
    recipients.scheduleRedaction(order.id(), clock.instant().plus(90, ChronoUnit.DAYS));
    hooks.afterOutbox();
  }

  void handleUpdated(InboxMessage message) {
    TsfAccount account = requireTsfAccount(message);
    String externalOrderId = requiredText(message.payload().path("data"), "order_id");
    SalesOrder order = requireOrder(account, externalOrderId);
    JsonNode data = message.payload().path("data");
    if (data.has("recipient")) {
      Recipient recipient = CreatedPayload.parseRecipient(data.path("recipient"));
      recipients.update(order.id(), recipient);
    } else if (data.has("note")) {
      log.info("order.updated note ignored for external_order_id={}", externalOrderId);
    }
    hooks.afterOutbox();
  }

  private void maybeReadyToPick(
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

  private SalesOrder applyHold(SalesOrder order, String holdReason, String holdNote) {
    TransitionResult hold =
        stateMachine.applyHoldReason(order, holdReason, holdNote, "intake", "SYSTEM");
    return hold.order();
  }

  private SalesOrder applyPayment(SalesOrder order, String to, Instant paidAt, TsfAccount account) {
    TransitionResult paid =
        stateMachine.applyPaymentStatus(
            order, to, "TSF paid", "TSF", paidAt, guard(account, order, List.of()));
    return paid.order();
  }

  private GuardContext guard(TsfAccount account, SalesOrder order, List<ReserveItem> mappedItems) {
    boolean enforced = stockEnforced(account);
    boolean covers = !enforced || coverage.covers(order.id(), mappedItems, clock.instant());
    return new GuardContext(enforced, covers, clock.instant());
  }

  private static boolean stockEnforced(TsfAccount account) {
    return OrderStockEnforcement.enforced(account.mode(), account.status());
  }

  private List<LineMapping> insertLines(
      UUID orderId, UUID channelAccountId, List<CreatedLine> payloadLines) {
    List<LineMapping> mapped = new ArrayList<>();
    for (CreatedLine line : payloadLines) {
      UUID skuId = lookupSku(channelAccountId, line.listingSkuId());
      UUID lineId = UuidV7.generate();
      lines.insert(
          lineId,
          orderId,
          skuId,
          line.lineId(),
          line.listingSkuId(),
          line.name(),
          line.qty(),
          line.unitPrice(),
          BigDecimal.ZERO,
          line.unitPrice().multiply(BigDecimal.valueOf(line.qty())));
      boolean stockControl = listingStockControl(channelAccountId, line.listingSkuId());
      mapped.add(
          new LineMapping(
              lineId,
              line.lineId(),
              skuId,
              line.qty(),
              line.listingSkuId(),
              stockControl,
              skuId != null));
    }
    return mapped;
  }

  private UUID lookupSku(UUID channelAccountId, String externalSkuId) {
    return listingSku(channelAccountId, externalSkuId);
  }

  private UUID listingSku(UUID channelAccountId, String externalSkuId) {
    List<UUID> ids =
        jdbc.query(
            """
            SELECT sku_id FROM channel_listing
            WHERE channel_account_id = ? AND external_sku_id = ?
            """,
            (rs, row) -> rs.getObject("sku_id", UUID.class),
            channelAccountId,
            externalSkuId);
    return ids.isEmpty() ? null : ids.get(0);
  }

  private boolean listingStockControl(UUID channelAccountId, String externalSkuId) {
    List<Boolean> flags =
        jdbc.query(
            """
            SELECT stock_control FROM channel_listing
            WHERE channel_account_id = ? AND external_sku_id = ?
            """,
            (rs, row) -> rs.getBoolean("stock_control"),
            channelAccountId,
            externalSkuId);
    return !flags.isEmpty() && flags.get(0);
  }

  private List<ReserveItem> mappedReserveItems(UUID orderId) {
    return lines.findByOrderId(orderId).stream()
        .filter(line -> line.skuId() != null)
        .map(line -> ReserveItem.of(line.skuId(), line.qty()))
        .toList();
  }

  private void recordOversell(
      TsfAccount account, List<LineMapping> mapped, List<Shortfall> shortfalls) {
    String mode = account.mode();
    boolean oversell =
        mapped.stream()
            .filter(LineMapping::mapped)
            .filter(line -> lineInShortfall(line, shortfalls))
            .anyMatch(
                line -> "ACTIVE".equals(mode) || ("CONTROL".equals(mode) && line.stockControl()));
    if (oversell) {
      meters.counter(BUSINESS_OVERSELL_METRIC, "mode", mode).increment();
    }
  }

  private static boolean lineInShortfall(LineMapping line, List<Shortfall> shortfalls) {
    if (line.skuId() == null) {
      return false;
    }
    return shortfalls.stream()
        .anyMatch(sf -> sf.skuId().equals(line.skuId()) || sf.requestedBy().contains(line.skuId()));
  }

  private String shadowDiffJson(List<LineMapping> mapped, List<Shortfall> shortfalls) {
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

  private static Instant orderedAt(InboxMessage message, CreatedPayload payload) {
    if (payload.orderedAt() != null) {
      return payload.orderedAt();
    }
    return eventOccurredAt(message);
  }

  private static Instant eventOccurredAt(InboxMessage message) {
    JsonNode occurred = message.payload().path("occurred_at");
    if (occurred.isString() && !occurred.asString().isBlank()) {
      return Instant.parse(occurred.asString());
    }
    throw new NonRetryableInboxException("occurred_at is required");
  }

  private static UUID parseUuid(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return UUID.fromString(raw);
    } catch (IllegalArgumentException ex) {
      return null;
    }
  }

  private String unknownSkuHoldNote(List<LineMapping> mapped, List<ReserveItem> failedItems) {
    StringBuilder note = new StringBuilder("mapped sku not found");
    Set<UUID> failedSkus = new LinkedHashSet<>();
    failedItems.forEach(item -> failedSkus.add(item.skuId()));
    for (LineMapping line : mapped) {
      if (line.skuId() != null && failedSkus.contains(line.skuId())) {
        note.append(';').append(line.listingSkuId());
      }
    }
    return note.toString();
  }

  private String unknownSkuHoldNoteFromLines(UUID orderId, List<ReserveItem> failedItems) {
    List<LineMapping> mapped =
        lines.findByOrderId(orderId).stream()
            .map(
                line ->
                    new LineMapping(
                        line.id(),
                        line.externalLineId(),
                        line.skuId(),
                        line.qty(),
                        line.externalSkuId(),
                        false,
                        line.skuId() != null))
            .toList();
    return unknownSkuHoldNote(mapped, failedItems);
  }

  private String componentlessBundleShadowJson(List<ReserveItem> reserveItems) {
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

  private static String shortfallNote(List<Shortfall> shortfalls) {
    StringBuilder note = new StringBuilder();
    for (Shortfall sf : shortfalls) {
      if (!note.isEmpty()) {
        note.append(';');
      }
      note.append(sf.skuId()).append(':').append(sf.requested()).append('/').append(sf.available());
    }
    return note.toString();
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node.path(field);
    if (!value.isString() || value.asString().isBlank()) {
      throw new NonRetryableInboxException(field + " is required");
    }
    return value.asString();
  }

  private static String optionalText(JsonNode node, String field) {
    JsonNode value = node.path(field);
    if (!value.isString()) {
      return "";
    }
    return value.asString("");
  }

  private static String requiredText(JsonNode node, String field) {
    JsonNode value = node.path(field);
    if (!value.isString() || value.asString().isBlank()) {
      throw new NonRetryableInboxException("data." + field + " is required");
    }
    return value.asString();
  }

  record LineMapping(
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

  record CreatedLine(
      String lineId,
      String listingSkuId,
      String sellerSku,
      String name,
      int qty,
      BigDecimal unitPrice,
      boolean ignoredStockControl) {}

  record CreatedPayload(
      String orderId,
      String reservationId,
      String paymentMethod,
      Instant paymentExpiresAt,
      String currency,
      BigDecimal subtotal,
      BigDecimal shippingFee,
      BigDecimal discount,
      BigDecimal grandTotal,
      Instant orderedAt,
      Instant shipBy,
      Recipient recipient,
      Instant redactAfter,
      List<CreatedLine> lines) {

    static CreatedPayload parse(JsonNode data, Clock clock) {
      String orderId = requiredText(data, "order_id");
      String reservationId = optionalText(data, "reservation_id");
      String paymentMethod = requiredText(data, "payment_method");
      Instant paymentExpiresAt = instant(data, "payment_expires_at");
      if ("PREPAID".equals(paymentMethod) && paymentExpiresAt == null) {
        throw new NonRetryableInboxException("payment_expires_at is required for PREPAID");
      }
      String currency = requiredText(data, "currency");
      if (!"THB".equals(currency)) {
        throw new NonRetryableInboxException("currency must be THB");
      }
      JsonNode totals = data.path("totals");
      BigDecimal subtotal = decimal(totals, "subtotal");
      BigDecimal shipping = decimal(totals, "shipping_fee");
      BigDecimal discount = decimal(totals, "discount");
      BigDecimal grand = decimal(totals, "grand_total");
      Recipient recipient = parseRecipient(data.path("recipient"));
      Instant shipBy = instant(data, "ship_by");
      List<CreatedLine> lines = new ArrayList<>();
      for (JsonNode line : data.path("lines")) {
        lines.add(
            new CreatedLine(
                requiredText(line, "line_id"),
                requiredText(line, "listing_sku_id"),
                requiredText(line, "seller_sku"),
                requiredText(line, "name"),
                line.path("qty").asInt(),
                decimal(line, "unit_price"),
                false));
      }
      if (lines.isEmpty()) {
        throw new NonRetryableInboxException("data.lines must not be empty");
      }
      Instant orderedAt = instant(data, "ordered_at");
      Instant redactAfter = null;
      return new CreatedPayload(
          orderId,
          reservationId,
          paymentMethod,
          paymentExpiresAt,
          currency,
          subtotal,
          shipping,
          discount,
          grand,
          orderedAt,
          shipBy,
          recipient,
          redactAfter,
          lines);
    }

    static Recipient parseRecipient(JsonNode node) {
      String name = requiredText(node, "name");
      String phone =
          node.has("phone") && node.path("phone").isString() ? node.path("phone").asString() : null;
      JsonNode address = node.path("address");
      String line1 = requiredText(address, "line1");
      String district = requiredText(address, "district");
      String province = requiredText(address, "province");
      String postcode = requiredText(address, "postcode");
      String addressJson =
          "{\"line1\":\""
              + escape(line1)
              + "\",\"district\":\""
              + escape(district)
              + "\",\"province\":\""
              + escape(province)
              + "\",\"postcode\":\""
              + escape(postcode)
              + "\"}";
      return new Recipient(name, phone, addressJson, province, postcode);
    }

    private static String escape(String value) {
      return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static BigDecimal decimal(JsonNode node, String field) {
      if (!node.has(field)) {
        throw new NonRetryableInboxException(field + " is required");
      }
      return node.path(field).decimalValue();
    }

    private static Instant instant(JsonNode node, String field) {
      if (!node.has(field) || node.path(field).asString().isBlank()) {
        return null;
      }
      return Instant.parse(node.path(field).asString());
    }
  }
}
