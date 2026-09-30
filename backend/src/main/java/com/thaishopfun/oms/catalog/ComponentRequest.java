package com.thaishopfun.oms.catalog;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.UUID;

/** One bundle component. Exactly one of {@code component_sku_id} or {@code component_sku_code}. */
public record ComponentRequest(
    @JsonProperty("component_sku_id") UUID componentSkuId,
    @JsonProperty("component_sku_code") String componentSkuCode,
    Integer qty) {}
