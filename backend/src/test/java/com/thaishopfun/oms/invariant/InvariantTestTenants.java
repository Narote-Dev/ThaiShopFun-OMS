package com.thaishopfun.oms.invariant;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Tenants touched in the current test thread; {@link VerifyInvariantsExtension} checks these only.
 */
public final class InvariantTestTenants {

  private static final ThreadLocal<Set<UUID>> ACTIVE = ThreadLocal.withInitial(LinkedHashSet::new);

  private InvariantTestTenants() {}

  public static void register(UUID tenantId) {
    if (tenantId != null) {
      ACTIVE.get().add(tenantId);
    }
  }

  public static Set<UUID> drain() {
    Set<UUID> copy = Set.copyOf(ACTIVE.get());
    ACTIVE.get().clear();
    return copy;
  }
}
