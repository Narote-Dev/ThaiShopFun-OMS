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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class OrderCancelService {

  private static final Logger log = LoggerFactory.getLogger(OrderCancelService.class);

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

  record CancelPlan(
      SalesOrder order,
      AccountRow account,
      ChannelAdapter adapter,
      String idempotencyKey,
      String reason) {}

  public OrderViews.CancelResponseView requestCancel(
      UUID orderId, OrderViews.CancelRequestBody body) {
    OrderAccess.Actor actor = access.requireOwnerOrAdmin();
    String reason = body == null || body.reason() == null ? "" : body.reason().trim();
    String idempotencyKey = "cancel-request:" + orderId;

    CancelPlan plan =
        tx.read(
            () -> {
              SalesOrder order = orders.findById(orderId).orElseThrow(OrderApiException::notFound);
              if ("CHANNEL_CANCEL_PENDING".equals(order.holdReason())) {
                return null;
              }
              validateCancellable(order);
              AccountRow accountRow = loadAccount(order).orElseThrow(OrderApiException::notFound);
              ChannelAdapter adapter = adapters.require(Channel.valueOf(accountRow.channel()));
              if (!adapter.capabilities().supportsCancelRequest()) {
                throw new OrderApiException(
                    422, "CAPABILITY_UNSUPPORTED", "Cancel request is not supported");
              }
              return new CancelPlan(order, accountRow, adapter, idempotencyKey, reason);
            });

    if (plan == null) {
      SalesOrder pending =
          tx.read(() -> orders.findById(orderId).orElseThrow(OrderApiException::notFound));
      return new OrderViews.CancelResponseView(null, pending.externalOrderId(), "PENDING");
    }

    CancelResponse channelResponse;
    try {
      channelResponse =
          plan.adapter()
              .requestCancel(
                  plan.account().ref(),
                  plan.order().externalOrderId(),
                  plan.idempotencyKey(),
                  new CancelRequest(plan.reason()));
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
          try {
            validateCancellable(fresh);
          } catch (OrderApiException ex) {
            log.info(
                "cancel request ignored for order {} after channel call: {}",
                orderId,
                ex.getMessage());
            throw ex;
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
