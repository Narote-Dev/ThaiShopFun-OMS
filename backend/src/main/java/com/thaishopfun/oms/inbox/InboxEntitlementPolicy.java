package com.thaishopfun.oms.inbox;

import java.time.Clock;
import java.time.Instant;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * What the worker does with a claimed row.
 *
 * <p>{@code ACTIVE}, and {@code GRACE} that has not passed {@code entitlement_expires_at}, are
 * processed. GRACE still blocks user writes in {@code EntitlementGate}; inbound events keep flowing
 * so the shop does not lose orders during grace.
 *
 * <p>{@code SUSPENDED}, a passed expiry (even when the column still says {@code ACTIVE} or {@code
 * GRACE}), and any other status are deferred: the row stays {@code RECEIVED} or {@code FAILED}, the
 * claim does not consume an attempt, and {@code next_attempt_at} is pushed out. The event is not
 * deleted and does not become {@code DEAD} only because the shop is suspended.
 *
 * <p>{@code membership.changed} is processed in every state so a renewal can move the tenant back
 * to {@code ACTIVE}. {@code claim_inbox_batch} does not filter on entitlement.
 */
@Component
public class InboxEntitlementPolicy {

  static final String MEMBERSHIP_CHANGED = "membership.changed";

  /**
   * Ordered by {@code ent_ver}, not {@code aggregate_version}. Kept out of that version history.
   */
  private static final Set<String> ENT_VER_ORDERED = Set.of(MEMBERSHIP_CHANGED);

  private static final Set<String> ALWAYS = ENT_VER_ORDERED;

  static boolean ordersByEntVer(String eventType) {
    return ENT_VER_ORDERED.contains(eventType);
  }

  /** Quoted literals for {@code event_type NOT IN (...)}. Values are constants in this class. */
  static String entVerOrderedTypeLiterals() {
    StringBuilder literals = new StringBuilder();
    for (String type : ENT_VER_ORDERED) {
      if (!type.matches("[a-z0-9.]+")) {
        throw new IllegalStateException("event type is not a safe SQL literal");
      }
      if (literals.length() > 0) {
        literals.append(", ");
      }
      literals.append('\'').append(type).append('\'');
    }
    return literals.toString();
  }

  private final Clock clock;

  public InboxEntitlementPolicy(Clock clock) {
    this.clock = clock;
  }

  public boolean defer(String status, Instant expiresAt, String eventType) {
    // Step 1: System events have to run or a suspended shop can never come back.
    if (ALWAYS.contains(eventType)) {
      return false;
    }
    // Step 2: A passed expiry is inactive even if the status column has not been updated yet.
    if (expiresAt != null && expiresAt.isBefore(clock.instant())) {
      return true;
    }
    // Step 3: Unexpired GRACE still accepts inbound events.
    return !"ACTIVE".equals(status) && !"GRACE".equals(status);
  }
}
