package com.thaishopfun.oms.stockdoc;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A stock document. {@code lines} is set on single-document reads only. V4 keeps the warehouse on
 * each line, so the header has none; {@code warehouse_ids} lists the warehouses its lines use.
 */
public record StockDocumentView(
    UUID id,
    String type,
    String status,
    @JsonProperty("reference_no") String referenceNo,
    String note,
    @JsonProperty("count_started_at") Instant countStartedAt,
    @JsonProperty("posted_at") Instant postedAt,
    @JsonProperty("posted_by") UUID postedBy,
    @JsonProperty("line_count") int lineCount,
    @JsonProperty("warehouse_ids") List<UUID> warehouseIds,
    @JsonProperty("created_at") Instant createdAt,
    @JsonProperty("updated_at") Instant updatedAt,
    @JsonInclude(JsonInclude.Include.NON_NULL) List<LineView> lines) {

  /**
   * One line. {@code on_hand} and {@code reserved} are the current values of its inventory row
   * (null before the row exists), shown next to the line in the editor.
   */
  public record LineView(
      UUID id,
      @JsonProperty("sku_id") UUID skuId,
      @JsonProperty("sku_code") String skuCode,
      @JsonProperty("sku_name") String skuName,
      @JsonProperty("warehouse_id") UUID warehouseId,
      @JsonProperty("warehouse_code") String warehouseCode,
      int qty,
      @JsonProperty("system_qty_at_start") Integer systemQtyAtStart,
      @JsonProperty("counted_qty") Integer countedQty,
      @JsonProperty("reason_code") String reasonCode,
      @JsonProperty("on_hand") Integer onHand,
      Integer reserved,
      @JsonProperty("created_at") Instant createdAt) {}

  StockDocumentView withLines(List<LineView> list) {
    return new StockDocumentView(
        id,
        type,
        status,
        referenceNo,
        note,
        countStartedAt,
        postedAt,
        postedBy,
        lineCount,
        warehouseIds,
        createdAt,
        updatedAt,
        list);
  }
}
