package com.thaishopfun.oms.inbox;

import java.time.Duration;

/**
 * Bounds shared by startup and {@code claim_inbox_batch}. The SQL function rejects {@code p_lease >
 * interval '1 hour'}. {@link #MAX_LEASE} is that same ceiling.
 */
public final class InboxLimits {

  public static final Duration MAX_LEASE = Duration.ofHours(1);

  private InboxLimits() {}
}
