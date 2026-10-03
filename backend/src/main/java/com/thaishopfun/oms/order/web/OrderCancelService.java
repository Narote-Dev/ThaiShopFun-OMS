package com.thaishopfun.oms.order.web;

import com.thaishopfun.oms.channel.Channel;
import com.thaishopfun.oms.channel.ChannelAccountRef;
import com.thaishopfun.oms.channel.ChannelAdapter;
import com.thaishopfun.oms.channel.ChannelAdapterRegistry;
import com.thaishopfun.oms.channel.api.CancelRequest;
import com.thaishopfun.oms.channel.api.CancelResponse;
import com.thaishopfun.oms.channel.exception.UnsupportedCapabilityException;
import com.thaishopfun.oms.order.OrderStateMachine;
import com.thaishopfun.oms.order.SalesOrder;
import com.thaishopfun.oms.order.SalesOrderRepository;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class OrderCancelService {

  private final OrderAccess access;
  private final OrderTransactions tx;
  private final SalesOrderRepository orders;
  private final OrderStateMachine stateMachine;
  private final ChannelAdapterRegistry adapters;
  private final OrderAudit audit;
  private final JdbcTemplate jdbc;

  public OrderCancelService(
      OrderAccess access,
      OrderTransactions tx,
      SalesOrderRepository orders,
      OrderStateMachine stateMachine,
      ChannelAdapterRegistry adapters,
      OrderAudit audit,
      JdbcTemplate jdbc) {
    this.access = access;
    this.tx = tx;
    this.orders = orders;
    this.stateMachine = stateMachine;
    this.adapters = adapters;
    this.audit = audit;
    this.jdbc = jdbc;
  }

  public OrderViews.CancelResponseView requestCancel(
      UUID orderId, OrderViews.CancelRequestBody body) {
    OrderAccess.Actor actor = access.requireOwnerOrAdmin();
    SalesOrder order = orders.findById(orderId).orElseThrow(OrderApiException::notFound);
    if ("CHANNEL_CANCEL_PENDING".equals(order.holdReason())) {
      return new OrderViews.CancelResponseView(null, order.externalOrderId(), "PENDING");
    }
    validateCancellable(order);
    AccountRow accountRow = loadAccount(order).orElseThrow(OrderApiException::notFound);
    ChannelAdapter adapter = adapters.require(Channel.valueOf(accountRow.channel()));
    if (!adapter.capabilities().supportsCancelRequest()) {
      throw new OrderApiException(422, "CAPABILITY_UNSUPPORTED", "Cancel request is not supported");
    }
    String reason = body == null || body.reason() == null ? "" : body.reason().trim();
    String idempotencyKey = "cancel-request:" + orderId;
    CancelResponse channelResponse;
    try {
      channelResponse =
          adapter.requestCancel(
              accountRow.ref(), order.externalOrderId(), idempotencyKey, new CancelRequest(reason));
    } catch (UnsupportedCapabilityException ex) {
      throw new OrderApiException(422, "CAPABILITY_UNSUPPORTED", ex.getMessage());
    }
    return tx.write(
        () -> {
          SalesOrder fresh = orders.findById(orderId).orElseThrow(OrderApiException::notFound);
          if ("CHANNEL_CANCEL_PENDING".equals(fresh.holdReason())) {
            return new OrderViews.CancelResponseView(
                channelResponse.cancelRequestId(),
                fresh.externalOrderId(),
                channelResponse.status());
          }
          String holdNote = previousHoldNote(fresh);
          stateMachine.applyHoldReason(
              fresh, "CHANNEL_CANCEL_PENDING", holdNote, "cancel requested", "USER");
          audit.write(
              actor,
              "ORDER_CANCEL_REQUESTED",
              orderId,
              Map.of("hold_reason", fresh.holdReason()),
              Map.of("hold_reason", "CHANNEL_CANCEL_PENDING"));
          return new OrderViews.CancelResponseView(
              channelResponse.cancelRequestId(), fresh.externalOrderId(), channelResponse.status());
        });
  }

  private static void validateCancellable(SalesOrder order) {
    if (!"ACTIVE".equals(order.orderStatus())) {
      throw OrderApiException.conflict("ORDER_NOT_CANCELLABLE", "Order is not active");
    }
    if ("SHIPPED".equals(order.fulfillmentStatus())
        || "DELIVERED".equals(order.fulfillmentStatus())) {
      throw OrderApiException.conflict("ORDER_NOT_CANCELLABLE", "Order is already shipped");
    }
    if ("CANCELLED".equals(order.orderStatus())) {
      throw OrderApiException.conflict("ORDER_NOT_CANCELLABLE", "Order is cancelled");
    }
  }

  private static String previousHoldNote(SalesOrder order) {
    if ("NONE".equals(order.holdReason())) {
      return order.holdNote();
    }
    String previous = "previous hold: " + order.holdReason();
    if (order.holdNote() != null && !order.holdNote().isBlank()) {
      return previous + "; " + order.holdNote();
    }
    return previous;
  }

  private record AccountRow(ChannelAccountRef ref, String channel) {}

  private java.util.Optional<AccountRow> loadAccount(SalesOrder order) {
    return jdbc
        .query(
            """
            SELECT ca.id, ca.channel, t.tsf_shop_id
            FROM channel_account ca
            JOIN tenant t ON t.id = ca.tenant_id
            WHERE ca.id = ? AND ca.tenant_id = ?
            """,
            (rs, rowNum) ->
                new AccountRow(
                    new ChannelAccountRef(
                        order.tenantId(),
                        rs.getObject("id", UUID.class),
                        rs.getString("tsf_shop_id")),
                    rs.getString("channel")),
            order.channelAccountId(),
            order.tenantId())
        .stream()
        .findFirst();
  }
}
