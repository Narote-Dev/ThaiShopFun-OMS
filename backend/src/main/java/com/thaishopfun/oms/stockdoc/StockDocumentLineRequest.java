package com.thaishopfun.oms.stockdoc;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.UUID;

/**
 * A draft line. The SKU is {@code sku_id} or {@code sku_code}; a null warehouse is the default
 * warehouse. {@code qty} is ignored on COUNT lines (the post writes the applied correction there).
 */
public record StockDocumentLineRequest(
    @JsonProperty("sku_id") UUID skuId,
    @JsonProperty("sku_code") String skuCode,
    @JsonProperty("warehouse_id") UUID warehouseId,
    Integer qty,
    @JsonProperty("counted_qty") Integer countedQty,
    @JsonProperty("reason_code") String reasonCode) {}
