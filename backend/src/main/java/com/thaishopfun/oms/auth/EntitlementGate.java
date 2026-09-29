package com.thaishopfun.oms.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * ACTIVE with {@code oms} is full access. GRACE that has not expired is read-only. SUSPENDED, a
 * past {@code expires_at} (including GRACE), or a missing {@code oms} entitlement is {@code
 * ENTITLEMENT_INACTIVE}.
 */
@Component
public class EntitlementGate {

  private final ApiErrors errors;

  public EntitlementGate(ApiErrors errors) {
    this.errors = errors;
  }

  public boolean allow(
      HttpServletRequest request,
      HttpServletResponse response,
      UserClaims claims,
      TenantSnapshot snapshot)
      throws IOException {
    // Step 1: Suspended and expired memberships cannot call the API. Grace expires too.
    if (snapshot.expired(Instant.now())) {
      errors.write(request, response, 403, "ENTITLEMENT_INACTIVE", "Membership expired");
      return false;
    }
    if ("SUSPENDED".equals(snapshot.status())) {
      errors.write(request, response, 403, "ENTITLEMENT_INACTIVE", "Membership suspended");
      return false;
    }
    if (!"ACTIVE".equals(snapshot.status()) && !"GRACE".equals(snapshot.status())) {
      errors.write(request, response, 403, "ENTITLEMENT_INACTIVE", "Membership inactive");
      return false;
    }
    if (!claims.entitlements().contains("oms")) {
      errors.write(request, response, 403, "ENTITLEMENT_INACTIVE", "OMS entitlement is not active");
      return false;
    }
    // Step 2: Grace can read. Mutations are rejected before the controller.
    if ("GRACE".equals(snapshot.status()) && !isRead(request)) {
      errors.write(request, response, 403, "ENTITLEMENT_GRACE", "Membership is in a grace period");
      return false;
    }
    return true;
  }

  private static boolean isRead(HttpServletRequest request) {
    String method = request.getMethod();
    return "GET".equals(method) || "HEAD".equals(method) || "OPTIONS".equals(method);
  }
}
