package com.thaishopfun.oms.catalog;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.UUID;

/**
 * Create or full update. On create, {@code product_id} or {@code product_name} (a new product in
 * the same transaction) is required. On update, {@code product_id} is required.
 */
public record SkuRequest(
    @JsonProperty("product_id") UUID productId,
    @JsonProperty("product_name") String productName,
    @JsonProperty("sku_code") String skuCode,
    String name,
    String barcode,
    @JsonProperty("weight_g") Integer weightG,
    @JsonProperty("is_bundle") Boolean bundle) {}
