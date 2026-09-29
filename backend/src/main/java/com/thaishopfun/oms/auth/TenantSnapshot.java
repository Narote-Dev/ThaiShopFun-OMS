package com.thaishopfun.oms.auth;

import java.time.Instant;
import java.util.UUID;

/** Tenant row visible under the caller's RLS context, plus the membership role. */
public record TenantSnapshot(
    UUID tenantId,
    UUID userId,
    String name,
    String shopId,
    String tier,
    String role,
    String status,
    Instant expiresAt,
    long entVer) {

  boolean expired(Instant now) {
    return expiresAt != null && expiresAt.isBefore(now);
  }
}
