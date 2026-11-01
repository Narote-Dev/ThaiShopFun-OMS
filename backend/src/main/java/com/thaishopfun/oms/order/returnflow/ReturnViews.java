package com.thaishopfun.oms.order.returnflow;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class ReturnViews {

  private ReturnViews() {}

  public record ReturnSummaryView(
      UUID id,
      @JsonProperty("external_return_id") String externalReturnId,
      String type,
      String status,
      String reason,
      @JsonProperty("requested_at") Instant requestedAt,
      @JsonProperty("received_at") Instant receivedAt) {}

  public record ReturnLineView(
      @JsonProperty("return_line_id") UUID returnLineId,
      @JsonProperty("order_line_id") UUID orderLineId,
      @JsonProperty("external_line_id") String externalLineId,
      String name,
      int qty,
      String condition,
      @JsonProperty("restocked_qty") int restockedQty,
      @JsonProperty("damaged_qty") int damagedQty,
      @JsonProperty("sku_id") UUID skuId) {}

  public record ReturnDetailView(
      UUID id,
      @JsonProperty("order_id") UUID orderId,
      @JsonProperty("external_order_id") String externalOrderId,
      @JsonProperty("external_return_id") String externalReturnId,
      String type,
      String status,
      String reason,
      @JsonProperty("requested_at") Instant requestedAt,
      @JsonProperty("received_at") Instant receivedAt,
      List<ReturnLineView> lines,
      @JsonProperty("can_receive") boolean canReceive) {}

  public record ReceiveLineBody(
      @JsonProperty("return_line_id") UUID returnLineId, String condition, int qty) {}

  public record ReceiveBody(List<ReceiveLineBody> lines) {}

  public record ReceiveResponseView(
      UUID id, String status, @JsonProperty("received_at") Instant receivedAt) {}
}
