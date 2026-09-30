package com.thaishopfun.oms.catalog;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * SKU as returned by the API. {@code on_hand} and {@code reserved} are read-only sums over all
 * warehouses, null for a bundle (its stock is derived from components). {@code components} is set
 * on single-SKU reads only.
 */
public record SkuView(
    UUID id,
    @JsonProperty("product_id") UUID productId,
    @JsonProperty("product_name") String productName,
    @JsonProperty("sku_code") String skuCode,
    String name,
    String barcode,
    @JsonProperty("weight_g") Integer weightG,
    @JsonProperty("is_bundle") boolean bundle,
    @JsonProperty("on_hand") Integer onHand,
    Integer reserved,
    @JsonProperty("component_count") int componentCount,
    @JsonProperty("created_at") Instant createdAt,
    @JsonProperty("updated_at") Instant updatedAt,
    @JsonInclude(JsonInclude.Include.NON_NULL) List<ComponentView> components) {

  public record ComponentView(
      @JsonProperty("component_sku_id") UUID componentSkuId,
      @JsonProperty("sku_code") String skuCode,
      String name,
      int qty) {}

  SkuView withComponents(List<ComponentView> list) {
    return new SkuView(
        id,
        productId,
        productName,
        skuCode,
        name,
        barcode,
        weightG,
        bundle,
        onHand,
        reserved,
        componentCount,
        createdAt,
        updatedAt,
        list);
  }

  Map<String, Object> audit() {
    Map<String, Object> values = new LinkedHashMap<>();
    values.put("product_id", productId.toString());
    values.put("sku_code", skuCode);
    values.put("name", name);
    values.put("barcode", barcode);
    values.put("weight_g", weightG);
    values.put("is_bundle", bundle);
    return values;
  }
}
