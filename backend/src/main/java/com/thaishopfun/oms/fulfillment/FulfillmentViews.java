package com.thaishopfun.oms.fulfillment;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class FulfillmentViews {

  private FulfillmentViews() {}

  public record CandidateItem(
      UUID id,
      @JsonProperty("external_order_id") String externalOrderId,
      @JsonProperty("ordered_at") Instant orderedAt,
      @JsonProperty("ship_by") Instant shipBy,
      @JsonProperty("line_count") int lineCount) {}

  public record CandidatesView(List<CandidateItem> items) {}

  public record CreatePickListBody(@JsonProperty("order_ids") List<UUID> orderIds) {}

  public record PickListOrderRef(
      UUID id, @JsonProperty("external_order_id") String externalOrderId, int sequence) {}

  public record PickLine(
      @JsonProperty("sku_id") UUID skuId,
      @JsonProperty("sku_code") String skuCode,
      String barcode,
      String name,
      @JsonProperty("warehouse_code") String warehouseCode,
      int qty) {}

  public record PickListDetail(
      UUID id,
      @JsonProperty("warehouse_id") UUID warehouseId,
      @JsonProperty("warehouse_code") String warehouseCode,
      @JsonProperty("created_at") Instant createdAt,
      List<PickListOrderRef> orders,
      @JsonProperty("pick_lines") List<PickLine> pickLines) {}

  public record CreatePickListResponse(
      UUID id,
      @JsonProperty("order_count") int orderCount,
      @JsonProperty("pdf_url") String pdfUrl) {}

  public record PackLine(
      @JsonProperty("line_id") UUID lineId,
      @JsonProperty("sku_id") UUID skuId,
      @JsonProperty("sku_code") String skuCode,
      String barcode,
      String name,
      @JsonProperty("required_qty") int requiredQty,
      @JsonProperty("packed_qty") int packedQty,
      boolean mapped) {}

  public record PackRecipient(
      String name,
      String phone,
      String address,
      String province,
      String postcode,
      @JsonProperty("pii_status") String piiStatus) {}

  public record PackSession(
      UUID id,
      @JsonProperty("external_order_id") String externalOrderId,
      @JsonProperty("fulfillment_status") String fulfillmentStatus,
      long version,
      PackRecipient recipient,
      List<PackLine> lines,
      boolean complete) {}

  public record ScanBody(String scan) {}

  public record ScanResponse(
      @JsonProperty("line_id") UUID lineId,
      @JsonProperty("sku_code") String skuCode,
      @JsonProperty("packed_qty") int packedQty,
      @JsonProperty("required_qty") int requiredQty,
      boolean complete) {}

  public record CompletePackResponse(
      @JsonProperty("fulfillment_status") String fulfillmentStatus, long version) {}

  public record ShipCandidateItem(
      UUID id,
      @JsonProperty("external_order_id") String externalOrderId,
      @JsonProperty("ordered_at") Instant orderedAt,
      @JsonProperty("ship_by") Instant shipBy,
      @JsonProperty("has_shipment") boolean hasShipment,
      @JsonProperty("tracking_no") String trackingNo,
      String carrier) {}

  public record ShipCandidatesView(List<ShipCandidateItem> items) {}

  public record LabelBatchBody(@JsonProperty("order_ids") List<String> orderIds) {}

  public record ManualTrackingBody(
      String carrier, @JsonProperty("tracking_no") String trackingNo) {}

  public record ShipmentSummary(
      UUID id,
      String carrier,
      @JsonProperty("tracking_no") String trackingNo,
      String status,
      @JsonProperty("external_shipment_id") String externalShipmentId,
      @JsonProperty("manual") boolean manual) {}

  public record ManualTrackingResponse(
      @JsonProperty("fulfillment_status") String fulfillmentStatus, ShipmentSummary shipment) {}

  public record ShipConfirmResponse(
      @JsonProperty("fulfillment_status") String fulfillmentStatus,
      long version,
      ShipmentSummary shipment) {}

  public record PackingSlipLine(String skuCode, String name, int qty) {}

  public record PackingSlipData(
      String externalOrderId,
      String carrier,
      String trackingNo,
      String province,
      String postcode,
      Instant generatedAt,
      List<PackingSlipLine> lines) {}
}
