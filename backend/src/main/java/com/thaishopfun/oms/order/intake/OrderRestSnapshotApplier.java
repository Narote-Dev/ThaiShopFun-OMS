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
    /** Create committed; caller must run {@link #applyPaidCatchUp} in a new transaction. */
    APPLIED_NEEDS_PAID_CATCHUP,
    /** Create committed; caller must run {@link #applyCancelCatchUp} in a new transaction. */
    APPLIED_NEEDS_CANCEL_CATCHUP,
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
      String eventIdPrefix) {
    TsfAccount account =
        channels
            .tsfByExternalShopId(shopId)
            .orElseThrow(() -> new IllegalStateException("TSF account missing for shop"));
    Optional<SalesOrder> existing = orders.findByExternalId(account.id(), detail.orderId());
    long knownVersion =
        existing.map(o -> o.externalVersion() == null ? 0L : o.externalVersion()).orElse(0L);
    long snapshotVersion = detail.aggregateVersion();
    boolean cancelled = "CANCELLED".equalsIgnoreCase(nullToEmpty(detail.status()));
    if (snapshotVersion > 0 && snapshotVersion <= knownVersion) {
      if (needsPaidCatchUp(payment, currentOrder(account, detail.orderId()))) {
        return Outcome.APPLIED_NEEDS_PAID_CATCHUP;
      }
      return Outcome.SKIPPED;
    }
    boolean createdInThisApply = false;
    if (existing.isEmpty()) {
      // Step: Inbox gap refetch must not bootstrap from REST before order.created lands in OMS.
      if (eventIdPrefix.startsWith("gap:")) {
        return Outcome.SKIPPED;
      }
      long createVersion = createdAggregateVersion(snapshotVersion, payment, cancelled);
      InboxMessage created =
          message(
              tenantId,
              eventIdPrefix + ":created:" + createVersion,
              "order.created",
              detail.orderId(),
              createVersion,
              envelope(shopId, detail.orderId(), createVersion, "order.created", detailData(detail)));
      support.handleCreated(created);
      createdInThisApply = true;
      existing = orders.findByExternalId(account.id(), detail.orderId());
    }
    if (cancelled) {
      if (createdInThisApply) {
        return Outcome.APPLIED_NEEDS_CANCEL_CATCHUP;
      }
      applyCancelInTx(tenantId, shopId, account, detail, eventIdPrefix, snapshotVersion);
      touchExternalVersion(account.id(), detail.orderId(), snapshotVersion);
      return Outcome.APPLIED;
    }
    boolean changed = createdInThisApply;
    if (applyRecipientIfPresent(
        tenantId, shopId, account, detail, eventIdPrefix, snapshotVersion, knownVersion)) {
      changed = true;
    }
    if (needsPaidCatchUp(payment, currentOrder(account, detail.orderId()))) {
      if (createdInThisApply) {
        return Outcome.APPLIED_NEEDS_PAID_CATCHUP;
      }
      applyPaidInTx(tenantId, shopId, account, detail, eventIdPrefix, snapshotVersion);
      changed = true;
    }
    if (changed) {
      touchExternalVersion(account.id(), detail.orderId(), snapshotVersion);
      return Outcome.APPLIED;
    }
    return Outcome.SKIPPED;
  }

  public Outcome applyPaidCatchUp(
      UUID tenantId,
      String shopId,
      OrderDetail detail,
      PaymentStatus payment,
      String eventIdPrefix) {
    TsfAccount account =
        channels
            .tsfByExternalShopId(shopId)
            .orElseThrow(() -> new IllegalStateException("TSF account missing for shop"));
    SalesOrder current = currentOrder(account, detail.orderId());
    if (!needsPaidCatchUp(payment, current)) {
      return Outcome.SKIPPED;
    }
    applyPaidInTx(tenantId, shopId, account, detail, eventIdPrefix, detail.aggregateVersion());
    touchExternalVersion(account.id(), detail.orderId(), detail.aggregateVersion());
    return Outcome.APPLIED;
  }

  /** Applies the gap inbox webhook when REST is behind or missing fields from the snapshot. */
  public Outcome applyGapInboxEvent(
      UUID tenantId,
      String shopId,
      String eventType,
      JsonNode payload,
      String eventIdPrefix) {
    if (payload == null || payload.isNull() || eventType == null || eventType.isBlank()) {
      return Outcome.SKIPPED;
    }
    TsfAccount account =
        channels
            .tsfByExternalShopId(shopId)
            .orElseThrow(() -> new IllegalStateException("TSF account missing for shop"));
    String orderId = payload.path("data").path("order_id").asString(null);
    if (orderId == null || orderId.isBlank()) {
      orderId = payload.path("aggregate_id").asString(null);
    }
    if (orderId == null || orderId.isBlank()) {
      return Outcome.SKIPPED;
    }
    long version = payload.path("aggregate_version").asLong(0);
    long known =
        orders
            .findByExternalId(account.id(), orderId)
            .map(o -> o.externalVersion() == null ? 0L : o.externalVersion())
            .orElse(0L);
    if (version > 0 && version <= known && !"order.paid".equals(eventType)) {
      return Outcome.SKIPPED;
    }
    if (!orders.existsByExternalId(account.id(), orderId)) {
      return Outcome.SKIPPED;
    }
    ObjectNode envelope = payload.isObject() ? (ObjectNode) payload : json.valueToTree(payload);
    String eventId = payload.path("event_id").asString(eventIdPrefix + ":inbox");
    InboxMessage message =
        message(
            tenantId,
            eventId,
            eventType,
            orderId,
            version,
            envelope);
    switch (eventType) {
      case "order.updated":
        support.handleUpdated(message);
        break;
      case "order.paid":
        support.handlePaid(message);
        break;
      case "order.cancelled":
        support.handleCancelled(message);
        break;
      default:
        return Outcome.SKIPPED;
    }
    if (version > 0) {
      touchExternalVersion(account.id(), orderId, version);
    }
    return Outcome.APPLIED;
  }

  public Outcome applyCancelCatchUp(
      UUID tenantId,
      String shopId,
      OrderDetail detail,
      String eventIdPrefix) {
    TsfAccount account =
        channels
            .tsfByExternalShopId(shopId)
            .orElseThrow(() -> new IllegalStateException("TSF account missing for shop"));
    applyCancelInTx(tenantId, shopId, account, detail, eventIdPrefix, detail.aggregateVersion());
    touchExternalVersion(account.id(), detail.orderId(), detail.aggregateVersion());
    return Outcome.APPLIED;
  }

  private boolean applyRecipientIfPresent(
      UUID tenantId,
      String shopId,
      TsfAccount account,
      OrderDetail detail,
      String eventIdPrefix,
      long snapshotVersion,
      long knownVersion) {
    if (detail.recipient() == null || snapshotVersion <= knownVersion) {
      return false;
    }
    ObjectNode updatedData = json.createObjectNode();
    updatedData.put("order_id", detail.orderId());
    updatedData.set("recipient", json.valueToTree(detail.recipient()));
    InboxMessage updated =
        message(
            tenantId,
            eventIdPrefix + ":updated:" + snapshotVersion,
            "order.updated",
            detail.orderId(),
            snapshotVersion,
            envelope(shopId, detail.orderId(), snapshotVersion, "order.updated", updatedData));
    support.handleUpdated(updated);
    return true;
  }

  private void applyPaidInTx(
      UUID tenantId,
      String shopId,
      TsfAccount account,
      OrderDetail detail,
      String eventIdPrefix,
      long snapshotVersion) {
    InboxMessage paid =
        message(
            tenantId,
            eventIdPrefix + ":paid:" + snapshotVersion,
            "order.paid",
            detail.orderId(),
            snapshotVersion,
            envelope(
                shopId,
                detail.orderId(),
                snapshotVersion,
                "order.paid",
                json.createObjectNode().put("order_id", detail.orderId())));
    support.handlePaid(paid);
  }

  private void applyCancelInTx(
      UUID tenantId,
      String shopId,
      TsfAccount account,
      OrderDetail detail,
      String eventIdPrefix,
      long snapshotVersion) {
    SalesOrder current = orders.findByExternalId(account.id(), detail.orderId()).orElseThrow();
    if ("CANCELLED".equals(current.orderStatus())) {
      return;
    }
    InboxMessage cancelled =
        message(
            tenantId,
            eventIdPrefix + ":cancelled:" + snapshotVersion,
            "order.cancelled",
            detail.orderId(),
            snapshotVersion,
            envelope(
                shopId,
                detail.orderId(),
                snapshotVersion,
                "order.cancelled",
                json.createObjectNode().put("order_id", detail.orderId())));
    support.handleCancelled(cancelled);
  }

  private static boolean needsPaidCatchUp(PaymentStatus payment, SalesOrder current) {
    return payment != null
        && "PAID".equals(payment.status())
        && current != null
        && !"PAID".equals(current.paymentStatus())
        && !"CANCELLED".equals(current.orderStatus());
  }

  private SalesOrder currentOrder(TsfAccount account, String externalOrderId) {
    return orders.findByExternalId(account.id(), externalOrderId).orElse(null);
  }

  private void touchExternalVersion(UUID channelAccountId, String externalOrderId, long version) {
    if (version > 0) {
      orders.updateExternalVersion(channelAccountId, externalOrderId, version);
    }
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

  /**
   * REST create must not commit the snapshot aggregate version before paid/cancel catch-up; intake
   * would otherwise skip retries when catch-up fails.
   */
  private static long createdAggregateVersion(
      long snapshotVersion, PaymentStatus payment, boolean cancelled) {
    if (snapshotVersion <= 1) {
      return snapshotVersion;
    }
    if (cancelled) {
      return 1L;
    }
    if (payment != null && "PAID".equals(payment.status())) {
      return 1L;
    }
    return snapshotVersion;
  }
}
