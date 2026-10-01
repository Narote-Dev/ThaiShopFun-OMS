package com.thaishopfun.oms.stock;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Result of a post or void, stored under the idempotency key so a repeated call returns the same
 * body. {@code movements} are the ledger rows this call wrote ({@code ref_type =
 * stock_document_line}, {@code ref_id = line_id}).
 */
public record DocumentMovement(
    @JsonProperty("document_id") UUID documentId,
    StockDocumentType type,
    String status,
    @JsonProperty("posted_at") Instant postedAt,
    @JsonProperty("posted_by") UUID postedBy,
    @JsonProperty("voided_at") Instant voidedAt,
    List<Movement> movements) {

  public record Movement(
      @JsonProperty("ledger_id") UUID ledgerId,
      @JsonProperty("line_id") UUID lineId,
      @JsonProperty("sku_id") UUID skuId,
      @JsonProperty("warehouse_id") UUID warehouseId,
      String reason,
      @JsonProperty("delta_on_hand") int deltaOnHand) {}
}
