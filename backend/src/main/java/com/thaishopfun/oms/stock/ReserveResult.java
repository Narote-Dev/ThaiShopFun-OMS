package com.thaishopfun.oms.stock;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Outcome of {@link ReservationEngine#reserve}. {@code OUT_OF_STOCK} reserves nothing and is stored
 * under the idempotency key like a success, so a replay returns the same answer.
 */
public record ReserveResult(
    Status status,
    UUID reservationGroupId,
    StockOwner owner,
    Instant expiresAt,
    List<ReservedLine> lines,
    List<Shortfall> shortfalls) {

  public enum Status {
    RESERVED,
    OUT_OF_STOCK
  }

  public boolean reserved() {
    return status == Status.RESERVED;
  }

  static ReserveResult success(
      UUID groupId, StockOwner owner, Instant expiresAt, List<ReservedLine> lines) {
    return new ReserveResult(Status.RESERVED, groupId, owner, expiresAt, lines, List.of());
  }

  static ReserveResult outOfStock(StockOwner owner, List<Shortfall> shortfalls) {
    return new ReserveResult(Status.OUT_OF_STOCK, null, owner, null, List.of(), shortfalls);
  }
}
