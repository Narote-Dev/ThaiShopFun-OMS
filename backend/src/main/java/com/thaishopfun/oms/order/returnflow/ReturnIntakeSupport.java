package com.thaishopfun.oms.order.returnflow;

import com.thaishopfun.oms.inbox.InboxMessage;
import com.thaishopfun.oms.inbox.NonRetryableInboxException;
import com.thaishopfun.oms.order.ChannelAccountLookup;
import com.thaishopfun.oms.order.ChannelAccountLookup.TsfAccount;
import com.thaishopfun.oms.order.OrderIntakeHooks;
import com.thaishopfun.oms.order.SalesOrder;
import com.thaishopfun.oms.order.SalesOrderRepository;
import com.thaishopfun.oms.order.intake.OrderIntakeSupport;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

@Service
public class ReturnIntakeSupport {

  private final ChannelAccountLookup channels;
  private final SalesOrderRepository orders;
  private final ReturnRepository returns;
  private final OrderIntakeHooks hooks;
  private final Clock clock;

  public ReturnIntakeSupport(
      ChannelAccountLookup channels,
      SalesOrderRepository orders,
      ReturnRepository returns,
      OrderIntakeHooks hooks,
      Clock clock) {
    this.channels = channels;
    this.orders = orders;
    this.returns = returns;
    this.hooks = hooks;
    this.clock = clock;
  }

  void handleRequested(InboxMessage message) {
    TsfAccount account = requireTsfAccount(message);
    JsonNode data = message.payload().path("data");
    String externalOrderId = requiredText(data, "order_id");
    String externalReturnId = requiredText(data, "return_id");
    String type = requiredText(data, "type");
    String reason = optionalText(data, "reason");
    SalesOrder order =
        orders
            .findByExternalId(account.id(), externalOrderId)
            .orElseThrow(
                () -> new NonRetryableInboxException("order not found: " + externalOrderId));

    Optional<ReturnRepository.ReturnRequestRow> existing =
        returns.findByExternalId(order.id(), externalReturnId);
    ReturnRepository.ReturnRequestRow request =
        existing.orElseGet(
            () ->
                returns.insertRequest(
                    order.id(),
                    externalReturnId,
                    type,
                    reason,
                    Instant.parse(message.payload().path("occurred_at").asString())));

    if (!"REQUESTED".equals(request.status()) && !"APPROVED".equals(request.status())) {
      return;
    }

    JsonNode lines = data.path("lines");
    if (!lines.isArray()) {
      throw new NonRetryableInboxException("return.requested lines must be an array");
    }
    for (JsonNode line : lines) {
      String externalLineId = requiredText(line, "line_id");
      int qty = line.path("qty").asInt(0);
      if (qty < 1) {
        throw new NonRetryableInboxException("return line qty must be positive");
      }
      UUID orderLineId =
          returns
              .orderLineIdByExternal(order.id(), externalLineId)
              .orElseThrow(
                  () ->
                      new NonRetryableInboxException(
                          "unknown order line " + externalLineId + " on " + externalOrderId));
      int shipped = returns.shippedQtyForLine(orderLineId, order.fulfillmentStatus());
      if (qty > shipped) {
        throw new NonRetryableInboxException(
            "RETURN_QTY_EXCEEDED: line "
                + externalLineId
                + " shipped "
                + shipped
                + " but return requested "
                + qty);
      }
      if (returns.findLine(request.id(), orderLineId).isEmpty()) {
        try {
          returns.insertLine(order.id(), request.id(), orderLineId, qty);
        } catch (DataAccessException ex) {
          throw mapReturnQtyExceeded(ex);
        }
      }
    }
    hooks.afterOutbox();
  }

  void handleDecided(InboxMessage message) {
    TsfAccount account = requireTsfAccount(message);
    JsonNode data = message.payload().path("data");
    String externalOrderId = requiredText(data, "order_id");
    String externalReturnId = requiredText(data, "return_id");
    String decision = requiredText(data, "decision");
    SalesOrder order =
        orders
            .findByExternalId(account.id(), externalOrderId)
            .orElseThrow(
                () -> new NonRetryableInboxException("order not found: " + externalOrderId));
    ReturnRepository.ReturnRequestRow request =
        returns
            .findByExternalId(order.id(), externalReturnId)
            .orElseThrow(
                () -> new NonRetryableInboxException("return not found: " + externalReturnId));

    if ("APPROVED".equals(decision)) {
      if ("REQUESTED".equals(request.status())) {
        returns.updateStatus(request.id(), "APPROVED");
      }
      return;
    }
    if ("REJECTED".equals(decision)) {
      if (!"REJECTED".equals(request.status()) && !"CLOSED".equals(request.status())) {
        returns.updateStatus(request.id(), "REJECTED");
      }
      return;
    }
    throw new NonRetryableInboxException("unsupported return decision: " + decision);
  }

  TsfAccount requireTsfAccount(InboxMessage message) {
    String shopId = text(message.payload(), "tsf_shop_id");
    return channels
        .tsfByExternalShopId(shopId)
        .orElseThrow(() -> new RuntimeException(OrderIntakeSupport.TSF_CHANNEL_ACCOUNT_MISSING));
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node.path(field);
    if (!value.isString() || value.asString().isBlank()) {
      throw new NonRetryableInboxException(field + " is required");
    }
    return value.asString();
  }

  private static String requiredText(JsonNode node, String field) {
    JsonNode value = node.path(field);
    if (!value.isString() || value.asString().isBlank()) {
      throw new NonRetryableInboxException("data." + field + " is required");
    }
    return value.asString();
  }

  private static String optionalText(JsonNode node, String field) {
    JsonNode value = node.path(field);
    if (!value.isString()) {
      return null;
    }
    String text = value.asString();
    return text.isBlank() ? null : text;
  }

  private static RuntimeException mapReturnQtyExceeded(DataAccessException ex) {
    Throwable root = ex.getMostSpecificCause();
    if (root instanceof java.sql.SQLException sql && "23514".equals(sql.getSQLState())) {
      String message = sql.getMessage();
      if (message != null && message.contains("RETURN_QTY_EXCEEDED")) {
        return new NonRetryableInboxException(message);
      }
    }
    return ex;
  }
}
