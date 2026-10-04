package com.thaishopfun.oms.listing;

import com.thaishopfun.oms.auth.TenantSessionService;
import com.thaishopfun.oms.auth.TenantSnapshot;
import com.thaishopfun.oms.tenant.TenantContext;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class ChannelListingAccess {

  private final TenantSessionService sessions;

  public ChannelListingAccess(TenantSessionService sessions) {
    this.sessions = sessions;
  }

  public record Actor(UUID tenantId, UUID userId) {}

  public Actor requireWriter() {
    TenantSnapshot snapshot =
        sessions.load(TenantContext.requireTenantId(), TenantContext.requireUserId());
    if (!"OWNER".equals(snapshot.role()) && !"ADMIN".equals(snapshot.role())) {
      throw ListingApiException.forbidden();
    }
    return new Actor(snapshot.tenantId(), snapshot.userId());
  }
}
