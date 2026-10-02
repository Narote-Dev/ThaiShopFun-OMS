package com.thaishopfun.oms.channel.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record OrderPage(
    List<OrderSummary> orders, @JsonProperty("next_cursor") String nextCursor) {}
