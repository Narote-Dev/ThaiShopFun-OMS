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
 * Resolves {@link TenantContext} for {@code /api/**} and always clears it. Lookup is read-only.
 * Upserts run only when a row is missing or {@code ent_ver} is newer. {@code auth.login} is written
 * only on that write path, not once per request and not per {@code jti}.
 */
final class TenantContextFilter extends OncePerRequestFilter {

  static final String UNAUTHORIZED_MESSAGE = "Invalid or expired token";

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
        errors.write(request, response, 401, "UNAUTHORIZED", UNAUTHORIZED_MESSAGE);
        return;
      }

      // Step 2: Map claims. The client sees one message; the reason stays in the debug log.
      UserClaims claims;
      try {
        claims = UserClaims.from(jwt);
      } catch (InvalidAccessTokenException ex) {
        log.debug("rejected access token: {}", ex.getMessage());
        errors.write(request, response, 401, "UNAUTHORIZED", UNAUTHORIZED_MESSAGE);
        return;
      }

      // Step 3: Read ids and ent_ver before any upsert. A stale or revoked token writes nothing.
      IdentityProvisioner.LoginLookup found =
          provisioner.lookup(claims.shopId(), claims.tsfUserId());
      if (found != null && found.tenantId() != null && found.entVer() > claims.entVer()) {
        errors.write(request, response, 401, "ENTITLEMENT_STALE", UNAUTHORIZED_MESSAGE);
        return;
      }
      if (found != null && "REVOKED".equals(found.membershipStatus())) {
        errors.write(request, response, 403, "MEMBERSHIP_REVOKED", "Membership revoked");
        return;
      }

      boolean wrote = IdentityProvisioner.needsProvision(found, claims);
      java.util.UUID tenantId;
      java.util.UUID userId;
      if (wrote) {
        IdentityProvisioner.Provisioned provisioned = provisioner.provision(claims, found);
        tenantId = provisioned.tenantId();
        userId = provisioned.userId();
      } else {
        tenantId = found.tenantId();
        userId = found.userId();
      }
      TenantContext.set(tenantId, userId);

      // Step 4: Load under RLS. Audit only if this request provisioned.
      TenantSnapshot snapshot = sessions.load(tenantId, userId);
      if (snapshot.entVer() > claims.entVer()) {
        errors.write(request, response, 401, "ENTITLEMENT_STALE", UNAUTHORIZED_MESSAGE);
        return;
      }
      if ("REVOKED".equals(snapshot.membershipStatus())) {
        errors.write(request, response, 403, "MEMBERSHIP_REVOKED", "Membership revoked");
        return;
      }
      if (wrote) {
        sessions.recordLogin(claims, snapshot, request.getRemoteAddr());
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
      // Step 5: The container reuses this thread. Drop the tenant before it returns to the pool.
      TenantContext.clear();
    }
  }
}
