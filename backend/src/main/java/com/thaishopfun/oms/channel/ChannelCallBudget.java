package com.thaishopfun.oms.channel;

import com.thaishopfun.oms.channel.exception.ChannelUnavailableException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/** Helpers for per-call deadline budgeting (thread-safe; no shared mutable state). */
public final class ChannelCallBudget {

  private ChannelCallBudget() {}

  public static Duration remaining(Clock clock, Instant deadline) {
    Duration remaining = Duration.between(clock.instant(), deadline);
    if (remaining.isZero() || remaining.isNegative()) {
      throw new ChannelUnavailableException("Channel call budget exceeded");
    }
    return remaining;
  }

  /** Per-request cap bounded by the remaining call deadline. */
  public static Duration transportTimeout(Clock clock, Duration perRequestCap, Instant deadline) {
    return minDuration(perRequestCap, remaining(clock, deadline));
  }

  public static Duration minDuration(Duration a, Duration b) {
    return a.compareTo(b) <= 0 ? a : b;
  }
}
