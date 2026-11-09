package com.thaishopfun.oms.fulfillment;

import com.thaishopfun.oms.inbox.InboxMessage;
import com.thaishopfun.oms.inbox.NonRetryableInboxException;
import com.thaishopfun.oms.order.ChannelAccountLookup;
import com.thaishopfun.oms.order.ChannelAccountLookup.TsfAccount;
import com.thaishopfun.oms.order.OrderOptimisticLockException;
import com.thaishopfun.oms.order.OrderStateException;
import com.thaishopfun.oms.order.OrderStateMachine;
import com.thaishopfun.oms.order.OrderStateMachine.GuardContext;
import com.thaishopfun.oms.order.SalesOrder;
import com.thaishopfun.oms.order.SalesOrderRepository;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

@Service
public class ShipmentIntakeSupport {

  private final ChannelAccountLookup channels;
  private final SalesOrderRepository orders;
  private final OrderStateMachine stateMachine;
  private final JdbcTemplate jdbc;
  private final Clock clock;

  public ShipmentIntakeSupport(
      ChannelAccountLookup channels,
      SalesOrderRepository orders,
      OrderStateMachine stateMachine,
      JdbcTemplate jdbc,
      Clock clock) {
    this.channels = channels;
    this.orders = orders;
    this.stateMachine = stateMachine;
    this.jdbc = jdbc;
    this.clock = clock;
  }

  @Transactional
  public void handle(InboxMessage message) {
    // Step 1: Resolve tenant channel and lock the order row.
    TsfAccount account = requireTsfAccount(message);
    JsonNode data = message.payload().path("data");
    String externalOrderId = requiredText(data, "order_id");
    String carrierStatus = requiredText(data, "status").toUpperCase(java.util.Locale.ROOT);
    SalesOrder order =
        orders
            .findByExternalId(account.id(), externalOrderId)
            .orElseThrow(
                () -> new NonRetryableInboxException("order not found: " + externalOrderId));
    order = orders.findByIdForUpdate(order.id()).orElseThrow();

    // Step 2: Sync shipment row when present (tracking + carrier status from TSF).
    String tracking = optionalText(data, "tracking_no");
    Instant occurred = Instant.parse(message.payload().path("occurred_at").asString());
    updateShipmentRow(order.id(), order.tenantId(), carrierStatus, tracking, occurred);

    // Step 3: Project DELIVERED onto fulfillment when the carrier reports delivery.
    if ("DELIVERED".equals(carrierStatus)) {
      if ("DELIVERED".equals(order.fulfillmentStatus())) {
        return;
      }
      if (!"SHIPPED".equals(order.fulfillmentStatus())) {
        throw new NonRetryableInboxException(
            "DELIVERED before SHIPPED for order " + externalOrderId);
      }
      try {
        stateMachine.applyFulfillmentStatus(
            order,
            "DELIVERED",
            "TSF shipment delivered",
            "TSF",
            new GuardContext(false, true, clock.instant()));
      } catch (OrderStateException | OrderOptimisticLockException ex) {
        throw new NonRetryableInboxException(ex.getMessage());
      }
    }
  }

  private void updateShipmentRow(
      UUID orderId, UUID tenantId, String status, String tracking, Instant occurred) {
    List<UUID> shipmentIds =
        jdbc.query(
            """
            SELECT id FROM shipment WHERE tenant_id = ? AND order_id = ? FOR UPDATE
            """,
            (rs, row) -> rs.getObject("id", UUID.class),
            tenantId,
            orderId);
    if (shipmentIds.isEmpty()) {
      return;
    }
    UUID shipmentId = shipmentIds.get(0);
    String mapped = mapShipmentStatus(status);
    if ("DELIVERED".equals(mapped)) {
      jdbc.update(
          """
          UPDATE shipment
          SET status = ?, tracking_no = coalesce(?, tracking_no),
              delivered_at = coalesce(delivered_at, ?), updated_at = now()
          WHERE tenant_id = ? AND id = ?
          """,
          mapped,
          blankToNull(tracking),
          Timestamp.from(occurred),
          tenantId,
          shipmentId);
      return;
    }
    jdbc.update(
        """
        UPDATE shipment
        SET status = ?, tracking_no = coalesce(?, tracking_no), updated_at = now()
        WHERE tenant_id = ? AND id = ?
        """,
        mapped,
        blankToNull(tracking),
        tenantId,
        shipmentId);
  }

  private static String mapShipmentStatus(String tsfStatus) {
    return switch (tsfStatus) {
      case "LABEL_READY",
          "PENDING",
          "SHIPPED",
          "IN_TRANSIT",
          "DELIVERED",
          "FAILED",
          "RETURNED_TO_SENDER" ->
          tsfStatus;
      default -> throw new NonRetryableInboxException("unknown shipment status: " + tsfStatus);
    };
  }

  private TsfAccount requireTsfAccount(InboxMessage message) {
    String shopId = message.payload().path("tsf_shop_id").asString();
    return channels
        .tsfByExternalShopId(shopId)
        .orElseThrow(() -> new NonRetryableInboxException("unknown tsf shop " + shopId));
  }

  private static String requiredText(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || !value.isString() || value.asString().isBlank()) {
      throw new NonRetryableInboxException(field + " is required");
    }
    return value.asString();
  }

  private static String optionalText(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || value.isNull() || !value.isString()) {
      return null;
    }
    String text = value.asString();
    return text.isBlank() ? null : text;
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }
}
