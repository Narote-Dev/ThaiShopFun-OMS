package com.thaishopfun.oms.fulfillment;

import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/ship")
class ShipController {

  private final ShipService ship;

  ShipController(ShipService ship) {
    this.ship = ship;
  }

  @GetMapping("/candidates")
  FulfillmentViews.ShipCandidatesView candidates() {
    return ship.candidates();
  }

  @PostMapping(
      value = "/labels",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = {MediaType.APPLICATION_PDF_VALUE, MediaType.APPLICATION_JSON_VALUE})
  ResponseEntity<byte[]> labelBatch(@RequestBody FulfillmentViews.LabelBatchBody body) {
    byte[] pdf = ship.labelBatchPdf(body);
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .contentType(MediaType.APPLICATION_PDF)
        .header("Content-Disposition", "inline; filename=\"labels-batch.pdf\"")
        .body(pdf);
  }

  @PostMapping("/orders/{orderId}/manual-tracking")
  ResponseEntity<FulfillmentViews.ManualTrackingResponse> manualTracking(
      @PathVariable UUID orderId, @RequestBody FulfillmentViews.ManualTrackingBody body) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(ship.registerManualTracking(orderId, body));
  }

  @PostMapping("/orders/{orderId}/confirm")
  ResponseEntity<FulfillmentViews.ShipConfirmResponse> confirm(@PathVariable UUID orderId) {
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ship.confirmShip(orderId));
  }
}
