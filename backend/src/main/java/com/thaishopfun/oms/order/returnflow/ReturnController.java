package com.thaishopfun.oms.order.returnflow;

import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/returns")
class ReturnController {

  private final ReturnService returns;

  ReturnController(ReturnService returns) {
    this.returns = returns;
  }

  @GetMapping("/{returnId}")
  ResponseEntity<ReturnViews.ReturnDetailView> detail(@PathVariable UUID returnId) {
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(returns.detail(returnId));
  }

  @PostMapping(value = "/{returnId}/receive", consumes = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<ReturnViews.ReceiveResponseView> receive(
      @PathVariable UUID returnId,
      @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestBody ReturnViews.ReceiveBody body) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(returns.receive(returnId, idempotencyKey, body));
  }
}
