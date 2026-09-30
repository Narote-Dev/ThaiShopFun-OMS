package com.thaishopfun.oms.stockdoc;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One keyset page of a SKU's ledger, newest first. {@code next_cursor} is null on the last page.
 */
public record StockHistoryPage(
    SkuRef sku, List<Entry> items, @JsonProperty("next_cursor") String nextCursor) {

  public record SkuRef(UUID id, @JsonProperty("sku_code") String skuCode, String name) {}

  /**
   * A ledger row. {@code on_hand_after} and {@code reserved_after} are the running totals of its
   * (sku, warehouse) row right after this entry, summed over the whole ledger in (created_at, id)
   * order.
   */
  public record Entry(
      UUID id,
      @JsonProperty("created_at") Instant createdAt,
      @JsonProperty("warehouse_id") UUID warehouseId,
      @JsonProperty("warehouse_code") String warehouseCode,
      String reason,
      @JsonProperty("delta_on_hand") int deltaOnHand,
      @JsonProperty("delta_reserved") int deltaReserved,
      @JsonProperty("on_hand_after") long onHandAfter,
      @JsonProperty("reserved_after") long reservedAfter,
      String actor,
      @JsonProperty("ref_type") String refType,
      @JsonProperty("ref_id") UUID refId,
      Link link) {}

  /**
   * Where the entry came from, derived from {@code ref_type}: {@code stock_document}, {@code
   * reservation} ({@code order_ref} only for an ORDER owner), {@code return_line} (id only), or
   * null without a reference.
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Link(
      String kind,
      @JsonProperty("document_id") UUID documentId,
      @JsonProperty("document_type") String documentType,
      @JsonProperty("document_status") String documentStatus,
      @JsonProperty("reference_no") String referenceNo,
      @JsonProperty("reservation_group_id") UUID reservationGroupId,
      @JsonProperty("owner_type") String ownerType,
      @JsonProperty("order_ref") String orderRef,
      @JsonProperty("return_line_id") UUID returnLineId) {}
}
