package com.thaishopfun.oms.stock;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.UUID;

/** Outcome of {@link StockMovements#restockReturn}: one {@code RETURN_RESTOCK} ledger row. */
public record RestockResult(
    @JsonProperty("return_line_id") UUID returnLineId,
    @JsonProperty("ledger_id") UUID ledgerId,
    @JsonProperty("sku_id") UUID skuId,
    @JsonProperty("warehouse_id") UUID warehouseId,
    int qty) {}
