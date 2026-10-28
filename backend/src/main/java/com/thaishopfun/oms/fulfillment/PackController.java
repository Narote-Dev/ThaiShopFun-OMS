package com.thaishopfun.oms.fulfillment;

import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/pack/orders")
class PackController {

  private final PackService pack;

  PackController(PackService pack) {
    this.pack = pack;
  }

  @GetMapping("/{orderId}")
  ResponseEntity<FulfillmentViews.PackSession> session(@PathVariable UUID orderId) {
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(pack.session(orderId));
  }

  @PostMapping("/{orderId}/scans")
  ResponseEntity<FulfillmentViews.ScanResponse> scan(
      @PathVariable UUID orderId, @RequestBody FulfillmentViews.ScanBody body) {
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(pack.scan(orderId, body));
  }

  @PostMapping("/{orderId}/complete")
  ResponseEntity<FulfillmentViews.CompletePackResponse> complete(@PathVariable UUID orderId) {
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(pack.complete(orderId));
  }
}
