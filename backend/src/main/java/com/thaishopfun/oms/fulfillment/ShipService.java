package com.thaishopfun.oms.fulfillment;

import com.thaishopfun.oms.auth.UuidV7;
import com.thaishopfun.oms.channel.Channel;
import com.thaishopfun.oms.channel.ChannelAccountRef;
import com.thaishopfun.oms.channel.ChannelAdapter;
import com.thaishopfun.oms.channel.ChannelAdapterRegistry;
import com.thaishopfun.oms.channel.api.LabelContent;
import com.thaishopfun.oms.channel.api.Shipment;
import com.thaishopfun.oms.channel.api.ShipmentRequest;
import com.thaishopfun.oms.channel.exception.ChannelClientException;
import com.thaishopfun.oms.channel.exception.UnsupportedCapabilityException;
import com.thaishopfun.oms.order.OrderOptimisticLockException;
import com.thaishopfun.oms.order.OrderStateException;
import com.thaishopfun.oms.order.OrderStateMachine;
import com.thaishopfun.oms.order.SalesOrder;
import com.thaishopfun.oms.order.SalesOrderRepository;
import com.thaishopfun.oms.order.web.OrderApiException;
import com.thaishopfun.oms.order.web.OrderAudit;
import com.thaishopfun.oms.order.web.OrderTransactions;
import com.thaishopfun.oms.outbox.OutboxAppender;
import com.thaishopfun.oms.outbox.OutboxDraft;
import com.thaishopfun.oms.stock.ReservationEngine;
import com.thaishopfun.oms.stock.StockOwner;
import com.thaishopfun.oms.tenant.TenantContext;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class ShipService {

  private static final Duration LABEL_RETENTION = Duration.ofDays(7);
  private static final int MAX_BATCH = LabelBatchPdfRenderer.MAX_LABELS;
  private static final int MAX_TRACKING_LENGTH = 64;
  private static final int MAX_CARRIER_LENGTH = 32;
  private static final String DEFAULT_CARRIER_REQUEST = "FLASH";

  private final JdbcTemplate jdbc;
  private final OrderTransactions tx;
  private final SalesOrderRepository orders;
  private final OrderStateMachine stateMachine;
  private final ChannelAdapterRegistry adapters;
  private final ReservationEngine stock;
  private final OutboxAppender outbox;
  private final FulfillmentAccess access;
  private final OrderAudit audit;
  private final ShipmentLabelCacheRepository labelCache;
  private final LabelBatchPdfRenderer labelBatchPdf;
  private final PackingSlipPdfRenderer packingSlipPdf;
  private final Clock clock;

  public ShipService(
      JdbcTemplate jdbc,
      OrderTransactions tx,
      SalesOrderRepository orders,
      OrderStateMachine stateMachine,
      ChannelAdapterRegistry adapters,
      ReservationEngine stock,
      OutboxAppender outbox,
      FulfillmentAccess access,
      OrderAudit audit,
      ShipmentLabelCacheRepository labelCache,
      LabelBatchPdfRenderer labelBatchPdf,
      PackingSlipPdfRenderer packingSlipPdf,
      Clock clock) {
    this.jdbc = jdbc;
    this.tx = tx;
    this.orders = orders;
    this.stateMachine = stateMachine;
    this.adapters = adapters;
    this.stock = stock;
    this.outbox = outbox;
    this.access = access;
    this.audit = audit;
    this.labelCache = labelCache;
    this.labelBatchPdf = labelBatchPdf;
    this.packingSlipPdf = packingSlipPdf;
    this.clock = clock;
  }

  public FulfillmentViews.ShipCandidatesView candidates() {
    List<FulfillmentViews.ShipCandidateItem> items =
        jdbc.query(
            """
            SELECT o.id, o.external_order_id, o.ordered_at, o.ship_by,
              sh.tracking_no, sh.carrier,
              (sh.id IS NOT NULL) AS has_shipment
            FROM sales_order o
            LEFT JOIN shipment sh ON sh.tenant_id = o.tenant_id AND sh.order_id = o.id
            WHERE o.fulfillment_status = 'PACKED'
              AND o.order_status = 'ACTIVE'
              AND o.hold_reason = 'NONE'
            ORDER BY o.ship_by NULLS LAST, o.ordered_at, o.id
            """,
            (rs, rowNum) ->
                new FulfillmentViews.ShipCandidateItem(
                    rs.getObject("id", UUID.class),
                    rs.getString("external_order_id"),
                    rs.getTimestamp("ordered_at").toInstant(),
                    optionalInstant(rs.getTimestamp("ship_by")),
                    rs.getBoolean("has_shipment"),
                    rs.getString("tracking_no"),
                    rs.getString("carrier")));
    return new FulfillmentViews.ShipCandidatesView(items);
  }

  public byte[] labelBatchPdf(FulfillmentViews.LabelBatchBody body) {
    FulfillmentAccess.Actor actor = access.requireWriter();
    if (body == null || body.orderIds() == null || body.orderIds().isEmpty()) {
      throw OrderApiException.fieldValidationFailed("order_ids", "order_ids is required");
    }
    if (body.orderIds().size() > MAX_BATCH) {
      throw OrderApiException.fieldValidationFailed(
          "order_ids", "At most " + MAX_BATCH + " orders per batch");
    }
    Set<UUID> unique = new LinkedHashSet<>(body.orderIds());
    List<byte[]> pages = new ArrayList<>();
    for (UUID orderId : unique) {
      pages.add(issueLabelPdf(orderId));
    }
    return labelBatchPdf.merge(pages);
  }

  public FulfillmentViews.ManualTrackingResponse registerManualTracking(
      UUID orderId, FulfillmentViews.ManualTrackingBody body) {
    FulfillmentAccess.Actor actor = access.requireWriter();
    String carrier = requireCarrier(body);
    String tracking = requireTracking(body);
    return tx.write(
        () -> {
          // Step 1: Lock order and validate it is ready to ship.
          SalesOrder order =
              orders.findByIdForUpdate(orderId).orElseThrow(OrderApiException::notFound);
          validatePacked(order);
          UUID warehouseId = resolveWarehouseId(orderId);
          UUID shipmentId = ensureShipmentRow(order, warehouseId);
          // Step 2: Persist manual carrier + tracking (no TSF label).
          jdbc.update(
              """
              UPDATE shipment
              SET carrier = ?, tracking_no = ?, external_shipment_id = NULL,
                  status = 'LABEL_READY', updated_at = now()
              WHERE tenant_id = ? AND id = ?
              """,
              carrier,
              tracking,
              TenantContext.requireTenantId(),
              shipmentId);
          // Step 3: Render packing slip into label cache for batch print.
          byte[] slip = packingSlipPdf.renderA6(buildPackingSlip(order, carrier, tracking));
          labelCache.upsert(shipmentId, "PACKING_SLIP", slip);
          audit.writeEntity(
              actor.tenantId(),
              actor.userId(),
              "SHIPMENT_MANUAL_TRACKING",
              "sales_order",
              orderId,
              Map.of(),
              Map.of("carrier", carrier, "tracking_no", tracking));
          ShipmentRow row = loadShipment(orderId).orElseThrow(OrderApiException::notFound);
          return new FulfillmentViews.ManualTrackingResponse(
              order.fulfillmentStatus(), toSummary(row));
        });
  }

  public FulfillmentViews.ShipConfirmResponse confirmShip(UUID orderId) {
    FulfillmentAccess.Actor actor = access.requireWriter();
    return tx.write(
        () -> {
          // Step 1: Lock order; idempotent when already shipped.
          SalesOrder order =
              orders.findByIdForUpdate(orderId).orElseThrow(OrderApiException::notFound);
          if ("SHIPPED".equals(order.fulfillmentStatus())
              || "DELIVERED".equals(order.fulfillmentStatus())) {
            ShipmentRow existing = loadShipment(orderId).orElseThrow(OrderApiException::notFound);
            return new FulfillmentViews.ShipConfirmResponse(
                order.fulfillmentStatus(), order.version(), toSummary(existing));
          }
          validatePacked(order);
          ShipmentRow shipment = loadShipment(orderId).orElseThrow(() -> missingShipment(orderId));
          if (shipment.trackingNo() == null || shipment.trackingNo().isBlank()) {
            throw OrderApiException.conflict("SHIPMENT_INCOMPLETE", "Tracking number is required");
          }
          if (!"LABEL_READY".equals(shipment.status()) && !"PENDING".equals(shipment.status())) {
            throw OrderApiException.conflict(
                "SHIPMENT_STATE", "Shipment is not ready to confirm: " + shipment.status());
          }
          // Step 2: Consume stock once per order (idempotent key).
          stock.consume(StockOwner.order(orderId.toString()), "ship:" + orderId);
          // Step 3: Move fulfillment to SHIPPED.
          Instant shippedAt = clock.instant();
          try {
            stateMachine.applyFulfillmentStatus(
                order, "SHIPPED", "confirm ship", actor.userId().toString(), null);
          } catch (OrderStateException ex) {
            throw OrderApiException.conflict("FULFILLMENT_STATE", ex.getMessage());
          } catch (OrderOptimisticLockException ex) {
            throw OrderApiException.conflict("ORDER_OPTIMISTIC_LOCK", ex.getMessage());
          }
          Instant cacheUntil = shippedAt.plus(LABEL_RETENTION);
          jdbc.update(
              """
              UPDATE shipment
              SET status = 'SHIPPED', shipped_at = ?, label_cached_until = ?, updated_at = now()
              WHERE tenant_id = ? AND id = ?
              """,
              Timestamp.from(shippedAt),
              Timestamp.from(cacheUntil),
              TenantContext.requireTenantId(),
              shipment.id());
          SalesOrder updated = orders.findById(orderId).orElseThrow(OrderApiException::notFound);
          // Step 4: Notify channel consumers via outbox.
          outbox.append(
              OutboxDraft.of(
                  "shipment",
                  shipment.externalShipmentId() != null
                      ? shipment.externalShipmentId()
                      : shipment.id().toString(),
                  "shipment.updated",
                  Map.of(
                      "order_id",
                      updated.externalOrderId(),
                      "status",
                      "SHIPPED",
                      "carrier",
                      shipment.carrier(),
                      "tracking_no",
                      shipment.trackingNo(),
                      "shipped_at",
                      shippedAt.toString()),
                  updated.version()));
          audit.writeEntity(
              actor.tenantId(),
              actor.userId(),
              "ORDER_SHIPPED",
              "sales_order",
              orderId,
              Map.of("fulfillment_status", order.fulfillmentStatus()),
              Map.of("fulfillment_status", updated.fulfillmentStatus()));
          ShipmentRow shipped = loadShipment(orderId).orElseThrow(OrderApiException::notFound);
          return new FulfillmentViews.ShipConfirmResponse(
              updated.fulfillmentStatus(), updated.version(), toSummary(shipped));
        });
  }

  private byte[] issueLabelPdf(UUID orderId) {
    SalesOrder order =
        tx.read(() -> orders.findById(orderId).orElseThrow(OrderApiException::notFound));
    validatePacked(order);
    ChannelPlan plan = tx.read(() -> channelPlan(order));
    ShipmentRow shipment =
        tx.write(
            () -> {
              UUID warehouseId = resolveWarehouseId(orderId);
              ensureShipmentRow(order, warehouseId);
              return loadShipment(orderId).orElseThrow(OrderApiException::notFound);
            });
    Optional<byte[]> cached = labelCache.findPdf(shipment.id());
    if (cached.isPresent() && !cacheExpired(shipment)) {
      return cached.get();
    }
    return fetchAndStoreLabel(order, plan, shipment.id());
  }

  private byte[] fetchAndStoreLabel(SalesOrder order, ChannelPlan plan, UUID shipmentId) {
    if (!plan.adapter().capabilities().supportsLabel()) {
      throw new OrderApiException(422, "CAPABILITY_UNSUPPORTED", "Labels are not supported");
    }
    ShipmentRow shipment = loadShipment(order.id()).orElseThrow(OrderApiException::notFound);
    Shipment channelShipment;
    if (shipment.externalShipmentId() != null) {
      channelShipment = shipment.asApi();
    } else {
      try {
        channelShipment =
            plan.adapter()
                .createShipment(
                    plan.account(),
                    order.externalOrderId(),
                    "shipment-create:" + order.id(),
                    new ShipmentRequest(DEFAULT_CARRIER_REQUEST));
      } catch (UnsupportedCapabilityException ex) {
        throw new OrderApiException(422, "CAPABILITY_UNSUPPORTED", ex.getMessage());
      }
      Shipment created = channelShipment;
      tx.write(
          () ->
              jdbc.update(
                  """
                  UPDATE shipment
                  SET external_shipment_id = ?, carrier = ?, tracking_no = ?, status = ?,
                      updated_at = now()
                  WHERE tenant_id = ? AND id = ?
                  """,
                  created.shipmentId(),
                  created.carrier(),
                  created.trackingNo(),
                  created.status(),
                  TenantContext.requireTenantId(),
                  shipmentId));
      channelShipment = created;
    }
    LabelContent label;
    try {
      label = plan.adapter().getLabel(plan.account(), channelShipment.shipmentId());
    } catch (ChannelClientException ex) {
      if (ex.statusCode() == 404 || "LABEL_NOT_AVAILABLE".equals(ex.errorCode())) {
        throw new OrderApiException(
            422,
            "LABEL_NOT_AVAILABLE",
            "TSF did not issue a label; enter carrier and tracking manually");
      }
      throw ex;
    }
    byte[] pdf = label.bytes();
    tx.write(
        () -> {
          labelCache.upsert(shipmentId, "LABEL", pdf);
          return Boolean.TRUE;
        });
    return pdf;
  }

  private ChannelPlan channelPlan(SalesOrder order) {
    AccountRow accountRow = loadAccount(order).orElseThrow(OrderApiException::notFound);
    ChannelAdapter adapter = adapters.optional(Channel.valueOf(accountRow.channel()));
    if (adapter == null) {
      throw OrderApiException.conflict("CHANNEL_MISSING", "Channel adapter is not configured");
    }
    return new ChannelPlan(
        new ChannelAccountRef(order.tenantId(), accountRow.id(), accountRow.externalShopId()),
        adapter);
  }

  private UUID ensureShipmentRow(SalesOrder order, UUID warehouseId) {
    Optional<ShipmentRow> existing = loadShipment(order.id());
    if (existing.isPresent()) {
      return existing.get().id();
    }
    UUID shipmentId = UuidV7.generate();
    jdbc.update(
        """
        INSERT INTO shipment (id, tenant_id, order_id, warehouse_id, status)
        VALUES (?, ?, ?, ?, 'PENDING')
        """,
        shipmentId,
        TenantContext.requireTenantId(),
        order.id(),
        warehouseId);
    return shipmentId;
  }

  private UUID resolveWarehouseId(UUID orderId) {
    List<UUID> fromReservation =
        jdbc.query(
            """
            SELECT sr.warehouse_id FROM stock_reservation sr
            WHERE sr.tenant_id = ? AND sr.owner_type = 'ORDER' AND sr.owner_ref = ?
              AND sr.status = 'ACTIVE'
            ORDER BY sr.id LIMIT 1
            """,
            (rs, rowNum) -> rs.getObject("warehouse_id", UUID.class),
            TenantContext.requireTenantId(),
            orderId.toString());
    if (!fromReservation.isEmpty()) {
      return fromReservation.get(0);
    }
    List<UUID> defaults =
        jdbc.query(
            """
            SELECT id FROM warehouse
            WHERE tenant_id = ? AND is_default = true
            ORDER BY created_at LIMIT 1
            """,
            (rs, rowNum) -> rs.getObject("id", UUID.class),
            TenantContext.requireTenantId());
    if (defaults.isEmpty()) {
      throw new OrderApiException(
          422, "WAREHOUSE_MISSING", "Default warehouse is required before shipping");
    }
    return defaults.get(0);
  }

  private Optional<ShipmentRow> loadShipment(UUID orderId) {
    List<ShipmentRow> rows =
        jdbc.query(
            """
            SELECT id, carrier, tracking_no, status, external_shipment_id, label_cached_until
            FROM shipment WHERE tenant_id = ? AND order_id = ?
            """,
            (rs, rowNum) ->
                new ShipmentRow(
                    rs.getObject("id", UUID.class),
                    rs.getString("carrier"),
                    rs.getString("tracking_no"),
                    rs.getString("status"),
                    rs.getString("external_shipment_id"),
                    optionalInstant(rs.getTimestamp("label_cached_until"))),
            TenantContext.requireTenantId(),
            orderId);
    return rows.stream().findFirst();
  }

  private FulfillmentViews.PackingSlipData buildPackingSlip(
      SalesOrder order, String carrier, String tracking) {
    List<FulfillmentViews.PackingSlipLine> lines =
        jdbc.query(
            """
            SELECT s.sku_code, ol.name, ol.qty
            FROM order_line ol
            LEFT JOIN sku s ON s.tenant_id = ol.tenant_id AND s.id = ol.sku_id
            WHERE ol.tenant_id = ? AND ol.order_id = ?
            ORDER BY ol.id
            """,
            (rs, rowNum) ->
                new FulfillmentViews.PackingSlipLine(
                    rs.getString("sku_code") == null ? "—" : rs.getString("sku_code"),
                    rs.getString("name"),
                    rs.getInt("qty")),
            TenantContext.requireTenantId(),
            order.id());
    String province = "—";
    String postcode = "—";
    List<RecipientSlice> recipient =
        jdbc.query(
            """
            SELECT province, postcode FROM order_recipient
            WHERE tenant_id = ? AND order_id = ?
            """,
            (rs, rowNum) -> new RecipientSlice(rs.getString("province"), rs.getString("postcode")),
            TenantContext.requireTenantId(),
            order.id());
    if (!recipient.isEmpty()) {
      province = nullToDash(recipient.get(0).province());
      postcode = nullToDash(recipient.get(0).postcode());
    }
    return new FulfillmentViews.PackingSlipData(
        order.externalOrderId(), carrier, tracking, province, postcode, clock.instant(), lines);
  }

  private static FulfillmentViews.ShipmentSummary toSummary(ShipmentRow row) {
    return new FulfillmentViews.ShipmentSummary(
        row.id(),
        row.carrier(),
        row.trackingNo(),
        row.status(),
        row.externalShipmentId(),
        row.externalShipmentId() == null);
  }

  private static void validatePacked(SalesOrder order) {
    if (!"PACKED".equals(order.fulfillmentStatus())) {
      throw OrderApiException.conflict(
          "ORDER_NOT_PACKED", "Order must be PACKED before label or ship");
    }
    if (!"ACTIVE".equals(order.orderStatus())) {
      throw OrderApiException.conflict("ORDER_NOT_ACTIVE", "Order is not active");
    }
    if (!"NONE".equals(order.holdReason())) {
      throw OrderApiException.conflict("ORDER_ON_HOLD", "Held orders cannot ship");
    }
  }

  private static OrderApiException missingShipment(UUID orderId) {
    return new OrderApiException(
        422, "SHIPMENT_REQUIRED", "Request a label or enter tracking before confirming ship");
  }

  private static String requireCarrier(FulfillmentViews.ManualTrackingBody body) {
    String carrier = body == null || body.carrier() == null ? "" : body.carrier().trim();
    if (carrier.isEmpty()) {
      throw OrderApiException.fieldValidationFailed("carrier", "carrier is required");
    }
    if (carrier.length() > MAX_CARRIER_LENGTH) {
      throw OrderApiException.fieldValidationFailed(
          "carrier", "carrier must be at most " + MAX_CARRIER_LENGTH + " characters");
    }
    return carrier;
  }

  private static String requireTracking(FulfillmentViews.ManualTrackingBody body) {
    String tracking = body == null || body.trackingNo() == null ? "" : body.trackingNo().trim();
    if (tracking.isEmpty()) {
      throw OrderApiException.fieldValidationFailed("tracking_no", "tracking_no is required");
    }
    if (tracking.length() > MAX_TRACKING_LENGTH) {
      throw OrderApiException.fieldValidationFailed(
          "tracking_no", "tracking_no must be at most " + MAX_TRACKING_LENGTH + " characters");
    }
    return tracking;
  }

  private boolean cacheExpired(ShipmentRow shipment) {
    return shipment.labelCachedUntil() != null
        && !shipment.labelCachedUntil().isAfter(clock.instant());
  }

  private static Instant optionalInstant(Timestamp value) {
    return value == null ? null : value.toInstant();
  }

  private record AccountRow(UUID id, String channel, String externalShopId) {}

  private record ChannelPlan(ChannelAccountRef account, ChannelAdapter adapter) {}

  private record RecipientSlice(String province, String postcode) {}

  private static String nullToDash(String value) {
    return value == null || value.isBlank() ? "—" : value;
  }

  private record ShipmentRow(
      UUID id,
      String carrier,
      String trackingNo,
      String status,
      String externalShipmentId,
      Instant labelCachedUntil) {

    Shipment asApi() {
      return new Shipment(externalShipmentId, null, carrier, trackingNo, status);
    }
  }

  private Optional<AccountRow> loadAccount(SalesOrder order) {
    return jdbc
        .query(
            """
            SELECT ca.id, ca.channel, ca.external_shop_id
            FROM channel_account ca
            WHERE ca.id = ? AND ca.tenant_id = ?
            """,
            (rs, rowNum) ->
                new AccountRow(
                    rs.getObject("id", UUID.class),
                    rs.getString("channel"),
                    rs.getString("external_shop_id")),
            order.channelAccountId(),
            order.tenantId())
        .stream()
        .findFirst();
  }
}
