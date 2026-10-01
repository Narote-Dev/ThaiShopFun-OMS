package com.thaishopfun.oms.stock;

import java.util.List;
import java.util.UUID;

/** Outcome of {@link ReservationEngine#adoptForOrder}. */
public record AdoptResult(
    Status status,
    UUID reservationGroupId,
    int transferredQty,
    int newlyReservedQty,
    int releasedQty,
    List<Shortfall> shortfalls) {

  public enum Status {
    ADOPTED,
    SHORT
  }

  public boolean adopted() {
    return status == Status.ADOPTED;
  }

  static AdoptResult adopted(UUID groupId, int transferred, int newlyReserved, int released) {
    return new AdoptResult(
        Status.ADOPTED, groupId, transferred, newlyReserved, released, List.of());
  }

  static AdoptResult shortfall(
      UUID groupId, int transferred, int released, List<Shortfall> shortfalls) {
    return new AdoptResult(Status.SHORT, groupId, transferred, 0, released, shortfalls);
  }
}
