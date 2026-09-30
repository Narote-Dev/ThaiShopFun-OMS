package com.thaishopfun.oms.catalog;

import com.thaishopfun.oms.auth.TenantSessionService;
import com.thaishopfun.oms.auth.TenantSnapshot;
import com.thaishopfun.oms.tenant.TenantContext;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Role check for catalog and warehouse writes. Reads are open to every member. GRACE is enforced
 * earlier by {@code EntitlementGate}, so it is not repeated here.
 */
@Component
public class CatalogAccess {

  private final TenantSessionService sessions;

  public CatalogAccess(TenantSessionService sessions) {
    this.sessions = sessions;
  }

  public record Actor(UUID tenantId, UUID userId) {}

  /** OWNER or ADMIN, and the shop is not in GRACE: the caller may write. Never throws for roles. */
  public boolean canWrite() {
    TenantSnapshot snapshot =
        sessions.load(TenantContext.requireTenantId(), TenantContext.requireUserId());
    return ("OWNER".equals(snapshot.role()) || "ADMIN".equals(snapshot.role()))
        && !"GRACE".equals(snapshot.status());
  }

  public Actor requireWriter() {
    TenantSnapshot snapshot =
        sessions.load(TenantContext.requireTenantId(), TenantContext.requireUserId());
    if (!"OWNER".equals(snapshot.role()) && !"ADMIN".equals(snapshot.role())) {
      throw new CatalogApiException(403, "FORBIDDEN", "OWNER or ADMIN role is required");
    }
    return new Actor(snapshot.tenantId(), snapshot.userId());
  }
}
