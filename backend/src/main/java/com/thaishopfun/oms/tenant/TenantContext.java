package com.thaishopfun.oms.tenant;

import java.util.UUID;

/**
 * Request-scoped tenant. One {@link ThreadLocal}, cleared in the authentication filter's {@code
 * finally}. Not inheritable: {@code @Async} workers start empty and must set a context themselves.
 */
public final class TenantContext {

  private record Scope(UUID tenantId, UUID userId) {}

  private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();

  private TenantContext() {}

  public static void set(UUID tenantId, UUID userId) {
    if (tenantId == null) {
      throw new IllegalArgumentException("tenantId is required");
    }
    CURRENT.set(new Scope(tenantId, userId));
  }

  public static UUID tenantId() {
    Scope scope = CURRENT.get();
    return scope == null ? null : scope.tenantId();
  }

  public static UUID userId() {
    Scope scope = CURRENT.get();
    return scope == null ? null : scope.userId();
  }

  public static UUID requireTenantId() {
    UUID tenantId = tenantId();
    if (tenantId == null) {
      throw new IllegalStateException("Tenant context is not set");
    }
    return tenantId;
  }

  public static UUID requireUserId() {
    UUID userId = userId();
    if (userId == null) {
      throw new IllegalStateException("User context is not set");
    }
    return userId;
  }

  public static void clear() {
    CURRENT.remove();
  }
}
