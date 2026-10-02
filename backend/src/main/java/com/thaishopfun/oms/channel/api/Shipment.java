package com.thaishopfun.oms.channel.api;

import com.fasterxml.jackson.annotation.JsonProperty;

public record Shipment(
    @JsonProperty("shipment_id") String shipmentId,
    @JsonProperty("order_id") String orderId,
    String carrier,
    @JsonProperty("tracking_no") String trackingNo,
    String status) {}
