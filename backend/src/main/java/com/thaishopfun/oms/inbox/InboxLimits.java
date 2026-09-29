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

  /**
   * Seconds for {@code TransactionTemplate.setTimeout}. That API truncates fractional seconds, so
   * this rounds up. The minimum is 1. Startup compares the lease to this same duration.
   */
  public static Duration handlerTransactionTimeout(Duration configured) {
    return Duration.ofSeconds(handlerTransactionTimeoutSeconds(configured));
  }

  public static int handlerTransactionTimeoutSeconds(Duration configured) {
    // Step 1: A missing or non-positive timeout still gets the one-second floor.
    if (configured == null || configured.isZero() || configured.isNegative()) {
      return 1;
    }
    // Step 2: Any leftover nanos mean the whole-second value is too short. Ceil it.
    long seconds = configured.getSeconds();
    if (configured.getNano() > 0) {
      seconds++;
    }
    if (seconds < 1) {
      seconds = 1;
    }
    return seconds > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) seconds;
  }
}
