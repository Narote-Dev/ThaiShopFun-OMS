package com.thaishopfun.oms.auth;

import com.thaishopfun.oms.tenant.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Resolves {@link TenantContext} for {@code /api/**} and always clears it. JIT provisioning runs
 * before the context is set. The entitlement gate runs while the context is set, before the
 * controller.
 */
final class TenantContextFilter extends OncePerRequestFilter {

  private static final Logger log = LoggerFactory.getLogger(TenantContextFilter.class);

  private final IdentityProvisioner provisioner;
  private final TenantSessionService sessions;
  private final EntitlementGate gate;
  private final ApiErrors errors;
  private final String requiredAudience;

  TenantContextFilter(
      IdentityProvisioner provisioner,
      TenantSessionService sessions,
      EntitlementGate gate,
      ApiErrors errors,
      String requiredAudience) {
    this.provisioner = provisioner;
    this.sessions = sessions;
    this.gate = gate;
    this.errors = errors;
    this.requiredAudience = requiredAudience;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    try {
      // Step 1: Service tokens are not logins and must not create a tenant.
      var jwt = RequiredAudienceFilter.currentJwt();
      if (jwt == null
          || jwt.getAudience() == null
          || !jwt.getAudience().contains(requiredAudience)) {
        errors.write(request, response, 401, "UNAUTHORIZED", "Invalid audience");
        return;
      }

      // Step 2: Map claims, then provision without a tenant setting.
      UserClaims claims;
      try {
        claims = UserClaims.from(jwt);
      } catch (InvalidAccessTokenException ex) {
        errors.write(request, response, 401, "UNAUTHORIZED", ex.getMessage());
        return;
      }
      IdentityProvisioner.Provisioned provisioned = provisioner.provision(claims);
      TenantContext.set(provisioned.tenantId(), provisioned.userId());

      // Step 3: Load under RLS. A newer ent_ver in the database forces a refresh.
      TenantSnapshot snapshot =
          sessions.open(
              claims, provisioned.tenantId(), provisioned.userId(), request.getRemoteAddr());
      if (snapshot.entVer() > claims.entVer()) {
        errors.write(
            request, response, 401, "ENTITLEMENT_STALE", "Token entitlement version is stale");
        return;
      }
      if (!gate.allow(request, response, claims, snapshot)) {
        return;
      }
      filterChain.doFilter(request, response);
    } catch (RuntimeException ex) {
      if (response.isCommitted()) {
        throw ex;
      }
      // The exception message can echo SQL parameters. Log the type only.
      log.warn("identity resolution failed: {}", ex.getClass().getSimpleName());
      errors.write(request, response, 500, "INTERNAL_ERROR", "Unexpected error");
    } finally {
      // Step 4: The container reuses this thread. Drop the tenant before it returns to the pool.
      TenantContext.clear();
    }
  }
}
