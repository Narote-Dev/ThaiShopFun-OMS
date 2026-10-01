package com.thaishopfun.oms.stock;

import java.util.List;

/** Outcome of {@link ReservationEngine#ensureOrderHold}. */
public record EnsureHoldResult(Status status, List<Shortfall> shortfalls) {

  public enum Status {
    HELD,
    SHORT
  }

  public boolean held() {
    return status == Status.HELD;
  }

  static EnsureHoldResult ok() {
    return new EnsureHoldResult(Status.HELD, List.of());
  }

  static EnsureHoldResult shortfall(List<Shortfall> shortfalls) {
    return new EnsureHoldResult(Status.SHORT, shortfalls);
  }
}
