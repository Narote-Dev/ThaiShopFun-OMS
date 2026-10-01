package com.thaishopfun.oms.stock;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.UUID;

/**
 * Why one document line (or one inventory row, for {@code BELOW_RESERVED}) blocks a post or void.
 * {@code onHand}, {@code reserved}, and {@code delta} are set for {@code BELOW_RESERVED} only.
 * {@code lineId} is null when the problem is the summed delta of several lines on one row.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LineProblem(
    @JsonProperty("line_id") UUID lineId,
    @JsonProperty("sku_id") UUID skuId,
    @JsonProperty("warehouse_id") UUID warehouseId,
    StockError error,
    String message,
    @JsonProperty("on_hand") Integer onHand,
    Integer reserved,
    Integer delta) {

  static LineProblem of(
      UUID lineId, UUID skuId, UUID warehouseId, StockError error, String message) {
    return new LineProblem(lineId, skuId, warehouseId, error, message, null, null, null);
  }
}
