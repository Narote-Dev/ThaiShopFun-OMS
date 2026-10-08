package com.thaishopfun.oms.order.intake;

import com.thaishopfun.oms.auth.UuidV7;
import com.thaishopfun.oms.channel.api.OrderDetail;
import com.thaishopfun.oms.channel.api.PaymentStatus;
import com.thaishopfun.oms.inbox.InboxMessage;
import com.thaishopfun.oms.order.ChannelAccountLookup;
import com.thaishopfun.oms.order.ChannelAccountLookup.TsfAccount;
import com.thaishopfun.oms.order.SalesOrder;
import com.thaishopfun.oms.order.SalesOrderRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Applies a TSF REST order snapshot through the normal inbox intake handlers. */
@Service
public class OrderRestSnapshotApplier {

  public enum Outcome {
    APPLIED,
    SKIPPED
  }

  private final OrderIntakeSupport support;
  private final ChannelAccountLookup channels;
  private final SalesOrderRepository orders;
  private final JsonMapper json;

  public OrderRestSnapshotApplier(
      OrderIntakeSupport support,
      ChannelAccountLookup channels,
      SalesOrderRepository orders,
      JsonMapper json) {
    this.support = support;
    this.channels = channels;
    this.orders = orders;
    this.json = json;
  }

  public Outcome apply(
      UUID tenantId,
      String shopId,
      OrderDetail detail,
      PaymentStatus payment,
      String eventIdPrefix,
      long inboxAggregateVersion) {
    // Step 1: Resolve channel account and compare channel aggregate version.
    TsfAccount account =
        channels
            .tsfByExternalShopId(shopId)
            .orElseThrow(() -> new IllegalStateException("TSF account missing for shop"));
    Optional<SalesOrder> existing = orders.findByExternalId(account.id(), detail.orderId());
    long knownVersion =
        existing.map(o -> o.externalVersion() == null ? 0L : o.externalVersion()).orElse(0L);
    if (detail.aggregateVersion() <= knownVersion) {
      return Outcome.SKIPPED;
    }
    // Step 2: Create when missing.
    if (existing.isEmpty()) {
      InboxMessage created =
          message(
              tenantId,
              eventIdPrefix + ":created:" + detail.aggregateVersion(),
              "order.created",
              detail.orderId(),
              detail.aggregateVersion(),
              envelope(
                  shopId,
                  detail.orderId(),
                  detail.aggregateVersion(),
                  "order.created",
                  detailData(detail)));
      support.handleCreated(created);
      existing = orders.findByExternalId(account.id(), detail.orderId());
    }
    // Step 3: Cancellation from optional REST status (unknown when absent).
    if ("CANCELLED".equalsIgnoreCase(nullToEmpty(detail.status()))) {
      SalesOrder current = orders.findByExternalId(account.id(), detail.orderId()).orElseThrow();
      if (!"CANCELLED".equals(current.orderStatus())) {
        InboxMessage cancelled =
            message(
                tenantId,
                eventIdPrefix + ":cancelled:" + detail.aggregateVersion(),
                "order.cancelled",
                detail.orderId(),
                inboxAggregateVersion > 0 ? inboxAggregateVersion : detail.aggregateVersion(),
                envelope(
                    shopId,
                    detail.orderId(),
                    detail.aggregateVersion(),
                    "order.cancelled",
                    json.createObjectNode().put("order_id", detail.orderId())));
        support.handleCancelled(cancelled);
      }
      touchExternalVersion(account.id(), detail.orderId(), detail.aggregateVersion());
      return Outcome.APPLIED;
    }
    // Step 4: Payment catch-up.
    SalesOrder current = orders.findByExternalId(account.id(), detail.orderId()).orElse(null);
    if (payment != null
        && "PAID".equals(payment.status())
        && current != null
        && !"PAID".equals(current.paymentStatus())
        && !"CANCELLED".equals(current.orderStatus())) {
      InboxMessage paid =
          message(
              tenantId,
              eventIdPrefix + ":paid:" + detail.aggregateVersion(),
              "order.paid",
              detail.orderId(),
              inboxAggregateVersion > 0 ? inboxAggregateVersion : detail.aggregateVersion(),
              envelope(
                  shopId,
                  detail.orderId(),
                  detail.aggregateVersion(),
                  "order.paid",
                  json.createObjectNode().put("order_id", detail.orderId())));
      support.handlePaid(paid);
    }
    // Step 5: Apply REST recipient snapshot when aggregate moved ahead of OMS.
    SalesOrder afterPayment = orders.findByExternalId(account.id(), detail.orderId()).orElse(null);
    if (afterPayment != null
        && detail.aggregateVersion() > knownVersion
        && detail.recipient() != null) {
      ObjectNode updatedData = json.createObjectNode();
      updatedData.put("order_id", detail.orderId());
      updatedData.set("recipient", json.valueToTree(detail.recipient()));
      InboxMessage updated =
          message(
              tenantId,
              eventIdPrefix + ":updated:" + detail.aggregateVersion(),
              "order.updated",
              detail.orderId(),
              inboxAggregateVersion > 0 ? inboxAggregateVersion : detail.aggregateVersion(),
              envelope(
                  shopId,
                  detail.orderId(),
                  detail.aggregateVersion(),
                  "order.updated",
                  updatedData));
      support.handleUpdated(updated);
      return Outcome.APPLIED;
    }
    touchExternalVersion(account.id(), detail.orderId(), detail.aggregateVersion());
    return Outcome.APPLIED;
  }

  private void touchExternalVersion(UUID channelAccountId, String externalOrderId, long version) {
    orders.updateExternalVersion(channelAccountId, externalOrderId, version);
  }

  private InboxMessage message(
      UUID tenantId,
      String eventId,
      String eventType,
      String aggregateId,
      long aggregateVersion,
      ObjectNode envelope) {
    return new InboxMessage(
        UuidV7.generate(),
        tenantId,
        "TSF",
        eventId,
        eventType,
        aggregateId,
        aggregateVersion,
        false,
        envelope);
  }

  private ObjectNode envelope(
      String shopId, String orderId, long version, String eventType, JsonNode data) {
    ObjectNode envelope = json.createObjectNode();
    envelope.put("event_id", "rest:" + orderId + ":" + version + ":" + eventType);
    envelope.put("event_type", eventType);
    envelope.put("schema_version", 1);
    envelope.put("occurred_at", Instant.now().toString());
    envelope.put("tsf_shop_id", shopId);
    envelope.put("aggregate_id", orderId);
    envelope.put("aggregate_version", version);
    envelope.set("data", data);
    return envelope;
  }

  private ObjectNode detailData(OrderDetail detail) {
    return json.valueToTree(detail);
  }

  private static String nullToEmpty(String value) {
    return value == null ? "" : value;
  }
}
