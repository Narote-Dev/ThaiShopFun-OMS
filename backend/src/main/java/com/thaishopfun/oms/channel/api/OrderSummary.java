package com.thaishopfun.oms.channel.api;

import java.time.Instant;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record OrderSummary(String orderId, Instant updatedAt, long aggregateVersion) {}
