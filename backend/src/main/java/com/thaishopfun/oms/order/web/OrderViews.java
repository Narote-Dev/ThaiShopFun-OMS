package com.thaishopfun.oms.order.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** JSON shapes for the orders user API (snake_case field names in serialization). */
public final class OrderViews {

  private OrderViews() {}

  public record Page<T>(
      List<T> items, long total, int limit, @JsonProperty("next_cursor") String nextCursor) {}

  public record ListItem(
      UUID id,
      @JsonProperty("external_order_id") String externalOrderId,
      @JsonProperty("order_status") String orderStatus,
      @JsonProperty("payment_status") String paymentStatus,
      @JsonProperty("fulfillment_status") String fulfillmentStatus,
      @JsonProperty("hold_reason") String holdReason,
      @JsonProperty("payment_method") String paymentMethod,
      @JsonProperty("grand_total") BigDecimal grandTotal,
      @JsonProperty("ordered_at") Instant orderedAt,
      @JsonProperty("ship_by") Instant shipBy,
      @JsonProperty("channel_account_id") UUID channelAccountId,
      String channel,
      @JsonProperty("phone_masked") String phoneMasked) {}

  public record TimelineEntry(
      String dimension,
      @JsonProperty("from_value") String fromValue,
      @JsonProperty("to_value") String toValue,
      String reason,
      String actor,
      Instant at) {}

  public record LineView(
      UUID id,
      @JsonProperty("external_line_id") String externalLineId,
      @JsonProperty("external_sku_id") String externalSkuId,
      @JsonProperty("sku_id") UUID skuId,
      @JsonProperty("sku_code") String skuCode,
      String name,
      int qty,
      @JsonProperty("unit_price") BigDecimal unitPrice,
      boolean mapped,
      boolean bundle,
      List<ComponentView> components) {}

  public record ComponentView(
      @JsonProperty("sku_id") UUID skuId,
      @JsonProperty("sku_code") String skuCode,
      String name,
      int qty) {}

  public record ReservationView(
      UUID id,
      @JsonProperty("sku_id") UUID skuId,
      @JsonProperty("sku_code") String skuCode,
      @JsonProperty("warehouse_id") UUID warehouseId,
      @JsonProperty("warehouse_code") String warehouseCode,
      int qty,
      String status,
      @JsonProperty("expires_at") Instant expiresAt) {}

  public record ShipmentView(
      UUID id,
      @JsonProperty("tracking_no") String trackingNo,
      String carrier,
      String status,
      @JsonProperty("shipped_at") Instant shippedAt) {}

  public record RecipientView(
      @JsonProperty("name_masked") String nameMasked,
      @JsonProperty("phone_masked") String phoneMasked,
      String province,
      String postcode,
      @JsonProperty("pii_status") String piiStatus) {}

  public record ChannelAccountView(UUID id, String channel, String mode, String status) {}

  public record DetailView(
      UUID id,
      @JsonProperty("external_order_id") String externalOrderId,
      @JsonProperty("order_status") String orderStatus,
      @JsonProperty("payment_status") String paymentStatus,
      @JsonProperty("fulfillment_status") String fulfillmentStatus,
      @JsonProperty("hold_reason") String holdReason,
      @JsonProperty("hold_detail") String holdDetail,
      @JsonProperty("hold_note") String holdNote,
      @JsonProperty("payment_method") String paymentMethod,
      String currency,
      BigDecimal subtotal,
      @JsonProperty("shipping_fee") BigDecimal shippingFee,
      BigDecimal discount,
      @JsonProperty("grand_total") BigDecimal grandTotal,
      @JsonProperty("ordered_at") Instant orderedAt,
      @JsonProperty("paid_at") Instant paidAt,
      @JsonProperty("ship_by") Instant shipBy,
      long version,
      @JsonProperty("channel_account") ChannelAccountView channelAccount,
      @JsonProperty("supports_cancel_request") boolean supportsCancelRequest,
      List<LineView> lines,
      List<ReservationView> reservations,
      List<ShipmentView> shipments,
      RecipientView recipient,
      List<TimelineEntry> timeline) {}

  public record HoldGroup(
      @JsonProperty("hold_reason") String holdReason,
      @JsonProperty("hold_detail") String holdDetail,
      long count,
      List<HoldSample> samples) {}

  public record HoldSample(
      UUID id,
      @JsonProperty("external_order_id") String externalOrderId,
      @JsonProperty("ordered_at") Instant orderedAt) {}

  public record HoldsView(List<HoldGroup> groups) {}

  public record CancelRequestBody(String reason) {}

  public record CancelResponseView(
      @JsonProperty("cancel_request_id") String cancelRequestId,
      @JsonProperty("order_id") String orderId,
      String status) {}
}
