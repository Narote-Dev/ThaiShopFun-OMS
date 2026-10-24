package com.thaishopfun.oms.channel.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record OrderDetail(
    String orderId,
    String reservationId,
    String paymentMethod,
    Instant paymentExpiresAt,
    String currency,
    Totals totals,
    Recipient recipient,
    Instant shipBy,
    List<Line> lines,
    Instant updatedAt,
    long aggregateVersion,
    String status) {

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record Totals(
      BigDecimal subtotal, BigDecimal shippingFee, BigDecimal discount, BigDecimal grandTotal) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record Recipient(String name, String phone, Address address) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record Address(String line1, String district, String province, String postcode) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record Line(
      String lineId,
      String listingSkuId,
      String sellerSku,
      String name,
      int qty,
      BigDecimal unitPrice) {}
}
