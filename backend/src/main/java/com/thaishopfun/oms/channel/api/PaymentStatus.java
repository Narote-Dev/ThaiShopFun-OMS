package com.thaishopfun.oms.channel.api;

import java.math.BigDecimal;
import java.util.List;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record PaymentStatus(String orderId, String status, List<Refund> refunds) {

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record Refund(
      String refundId, String returnId, String status, BigDecimal amount, String currency) {}
}
