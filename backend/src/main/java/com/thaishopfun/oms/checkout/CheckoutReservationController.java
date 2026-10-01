package com.thaishopfun.oms.checkout;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController
class CheckoutReservationController {

  private final CheckoutReserveService reserve;

  CheckoutReservationController(CheckoutReserveService reserve) {
    this.reserve = reserve;
  }

  @PostMapping(
      path = "/internal/v1/inventory/reservations",
      consumes = "application/json",
      produces = "application/json")
  ResponseEntity<JsonNode> create(
      @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestBody JsonNode body) {
    CheckoutReserveService.ReserveHttpResult result = reserve.reserve(idempotencyKey, body);
    return ResponseEntity.status(result.status()).body(result.body());
  }

  @DeleteMapping("/internal/v1/inventory/reservations/{reservationId}")
  ResponseEntity<Void> release(@PathVariable String reservationId) {
    reserve.release(reservationId);
    return ResponseEntity.noContent().build();
  }
}
