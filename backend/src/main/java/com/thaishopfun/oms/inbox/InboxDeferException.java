package com.thaishopfun.oms.inbox;

import java.time.Duration;

/** Order intake is waiting for a prerequisite event (for example {@code order.created}). */
public class InboxDeferException extends RuntimeException {

  private final Duration delay;

  public InboxDeferException(Duration delay) {
    super("inbox deferred " + delay);
    this.delay = delay;
  }

  public Duration delay() {
    return delay;
  }
}
