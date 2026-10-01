package com.thaishopfun.oms.channel.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.util.List;

public record PaymentStatus(
    @JsonProperty("order_id") String orderId, String status, List<Refund> refunds) {

  public record Refund(
      @JsonProperty("refund_id") String refundId,
      @JsonProperty("return_id") String returnId,
      String status,
      BigDecimal amount,
      String currency) {}
}
