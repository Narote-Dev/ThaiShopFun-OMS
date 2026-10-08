package com.thaishopfun.oms.invariant;

/** Outcome of one {@link InvariantJob#runOnce()} pass. */
public record InvariantJobResult(int violations, int checkFailed) {

  public boolean healthy() {
    return violations == 0 && checkFailed == 0;
  }
}
