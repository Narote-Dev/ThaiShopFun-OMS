package com.thaishopfun.oms.order.web;

import com.thaishopfun.oms.auth.TenantSessionService;
import com.thaishopfun.oms.auth.TenantSnapshot;
import com.thaishopfun.oms.catalog.CatalogApiException;
import com.thaishopfun.oms.tenant.TenantContext;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Role checks for order mutations. Reads are open to every member. */
@Component
public class OrderAccess {

  private final TenantSessionService sessions;

  public OrderAccess(TenantSessionService sessions) {
    this.sessions = sessions;
  }

  public record Actor(UUID tenantId, UUID userId, String role) {}

  public Actor actor() {
    TenantSnapshot snapshot =
        sessions.load(TenantContext.requireTenantId(), TenantContext.requireUserId());
    return new Actor(snapshot.tenantId(), snapshot.userId(), snapshot.role());
  }

  /** OWNER or ADMIN only (request cancel and similar channel actions). */
  public Actor requireOwnerOrAdmin() {
    Actor actor = actor();
    if (!"OWNER".equals(actor.role()) && !"ADMIN".equals(actor.role())) {
      throw new CatalogApiException(403, "FORBIDDEN", "OWNER or ADMIN role is required");
    }
    return actor;
  }
}
