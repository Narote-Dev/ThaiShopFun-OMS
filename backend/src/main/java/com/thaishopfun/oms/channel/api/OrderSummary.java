package com.thaishopfun.oms.channel.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;

public record OrderSummary(
    @JsonProperty("order_id") String orderId,
    @JsonProperty("updated_at") Instant updatedAt,
    @JsonProperty("aggregate_version") long aggregateVersion) {}
