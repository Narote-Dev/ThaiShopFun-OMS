package com.thaishopfun.oms.channel.api;

import com.fasterxml.jackson.annotation.JsonProperty;

public record CancelResponse(
    @JsonProperty("cancel_request_id") String cancelRequestId,
    @JsonProperty("order_id") String orderId,
    String status) {}
