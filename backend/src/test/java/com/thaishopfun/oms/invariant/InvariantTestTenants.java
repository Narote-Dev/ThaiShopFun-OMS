package com.thaishopfun.oms.invariant;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tenants touched during a test; {@link VerifyInvariantsExtension} checks these only. Uses a global
 * set so {@code @Timeout(threadMode = SEPARATE_THREAD)} tests still register tenants from worker
 * threads (sequential test execution keeps this safe).
 */
public final class InvariantTestTenants {

  private static final Set<UUID> ACTIVE = ConcurrentHashMap.newKeySet();

  private InvariantTestTenants() {}

  public static void register(UUID tenantId) {
    if (tenantId != null) {
      ACTIVE.add(tenantId);
    }
  }

  /** Clears registrations at the start of each test method. */
  public static void clear() {
    ACTIVE.clear();
  }

  /** Returns registered tenants and clears the set (used after each test). */
  public static Set<UUID> drain() {
    Set<UUID> copy = Set.copyOf(ACTIVE);
    ACTIVE.clear();
    return copy;
  }
}
