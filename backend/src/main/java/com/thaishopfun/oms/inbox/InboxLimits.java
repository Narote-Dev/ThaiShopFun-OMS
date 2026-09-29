package com.thaishopfun.oms.inbox;

import java.time.Duration;

/**
 * Bounds shared by startup and {@code claim_inbox_batch}. The SQL function rejects {@code p_lease >
 * interval '1 hour'}. {@link #MAX_LEASE} is that same ceiling.
 */
public final class InboxLimits {

  public static final Duration MAX_LEASE = Duration.ofHours(1);

  private InboxLimits() {}

  /**
   * Lease sent to {@code claim_inbox_batch}. Rounded up to the next millisecond so it is never
   * shorter than the configured duration the startup guard checked.
   */
  public static Duration claimedLease(Duration configured) {
    // Step 1: A zero or negative lease must stay invalid so startup still refuses it.
    if (configured == null || configured.isZero() || configured.isNegative()) {
      return Duration.ZERO;
    }
    // Step 2: Milliseconds truncate. Any leftover nanos round up, so the database lease is longer.
    long millis = configured.toMillis();
    if (configured.getNano() % 1_000_000L > 0) {
      millis++;
    }
    if (millis < 1) {
      millis = 1;
    }
    return Duration.ofMillis(millis);
  }
}
