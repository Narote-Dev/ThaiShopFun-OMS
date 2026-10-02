package com.thaishopfun.oms.channel.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record OrderDetail(
    @JsonProperty("order_id") String orderId,
    @JsonProperty("reservation_id") String reservationId,
    @JsonProperty("payment_method") String paymentMethod,
    @JsonProperty("payment_expires_at") Instant paymentExpiresAt,
    String currency,
    Totals totals,
    Recipient recipient,
    @JsonProperty("ship_by") Instant shipBy,
    List<Line> lines,
    @JsonProperty("updated_at") Instant updatedAt,
    @JsonProperty("aggregate_version") long aggregateVersion) {

  public record Totals(
      BigDecimal subtotal,
      @JsonProperty("shipping_fee") BigDecimal shippingFee,
      BigDecimal discount,
      @JsonProperty("grand_total") BigDecimal grandTotal) {}

  public record Recipient(String name, String phone, Address address) {}

  public record Address(String line1, String district, String province, String postcode) {}

  public record Line(
      @JsonProperty("line_id") String lineId,
      @JsonProperty("listing_sku_id") String listingSkuId,
      @JsonProperty("seller_sku") String sellerSku,
      String name,
      int qty,
      @JsonProperty("unit_price") BigDecimal unitPrice) {}
}
