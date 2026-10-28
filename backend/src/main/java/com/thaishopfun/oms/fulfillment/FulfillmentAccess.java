package com.thaishopfun.oms.fulfillment;

import com.thaishopfun.oms.auth.TenantSessionService;
import com.thaishopfun.oms.auth.TenantSnapshot;
import com.thaishopfun.oms.catalog.CatalogApiException;
import com.thaishopfun.oms.tenant.TenantContext;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Entitlement and role checks for pick/pack mutations. Reads follow the orders API. */
@Component
public class FulfillmentAccess {

  private final TenantSessionService sessions;

  public FulfillmentAccess(TenantSessionService sessions) {
    this.sessions = sessions;
  }

  public record Actor(UUID tenantId, UUID userId, String role) {}

  public Actor actor() {
    TenantSnapshot snapshot =
        sessions.load(TenantContext.requireTenantId(), TenantContext.requireUserId());
    return new Actor(snapshot.tenantId(), snapshot.userId(), snapshot.role());
  }

  /** Any shop member when entitlement allows writes (not GRACE). */
  public Actor requireWriter() {
    Actor actor = actor();
    TenantSnapshot snapshot =
        sessions.load(TenantContext.requireTenantId(), TenantContext.requireUserId());
    if ("GRACE".equals(snapshot.status())) {
      throw new CatalogApiException(403, "ENTITLEMENT_GRACE", "Shop is in grace period");
    }
    return actor;
  }
}
