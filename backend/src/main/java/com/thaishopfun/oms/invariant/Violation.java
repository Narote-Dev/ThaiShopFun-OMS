package com.thaishopfun.oms.invariant;

import java.util.List;
import java.util.UUID;

/** One invariant breach. Identifiers only — no PII. */
public record Violation(String code, UUID tenantId, List<UUID> entityIds) {

  public Violation {
    entityIds = entityIds == null ? List.of() : List.copyOf(entityIds);
  }

  public static Violation of(String code, UUID tenantId, List<UUID> entityIds) {
    return new Violation(code, tenantId, entityIds);
  }

  public static Violation schema(String code, List<UUID> entityIds) {
    return new Violation(code, null, entityIds);
  }
}
