package com.thaishopfun.oms.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collection;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Rejects a token whose {@code aud} is not the one this chain requires. On {@code /internal/**} the
 * client id ({@code azp}, else {@code client_id}) must also be on the allowlist. Status is 401 with
 * the same message as any other authentication failure.
 */
final class RequiredAudienceFilter extends OncePerRequestFilter {

  private static final Logger log = LoggerFactory.getLogger(RequiredAudienceFilter.class);

  private final ApiErrors errors;
  private final String requiredAudience;
  private final Collection<String> allowedClientIds;

  RequiredAudienceFilter(
      ApiErrors errors, String requiredAudience, Collection<String> allowedClientIds) {
    this.errors = errors;
    this.requiredAudience = requiredAudience;
    this.allowedClientIds = allowedClientIds;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    // Step 1: A user token on /internal, or the reverse, is an authentication failure.
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (!(authentication instanceof JwtAuthenticationToken jwtAuth)) {
      errors.unauthorized(request, response, null);
      return;
    }
    List<String> audience = jwtAuth.getToken().getAudience();
    if (audience == null || !audience.contains(requiredAudience)) {
      errors.write(
          request, response, 401, "UNAUTHORIZED", TenantContextFilter.UNAUTHORIZED_MESSAGE);
      return;
    }
    if (allowedClientIds != null) {
      // Step 2: Internal callers are an allowlist, not "any client that can sign".
      String clientId = jwtAuth.getToken().getClaimAsString("azp");
      if (clientId == null || clientId.isBlank()) {
        clientId = jwtAuth.getToken().getClaimAsString("client_id");
      }
      if (clientId == null || !allowedClientIds.contains(clientId)) {
        log.debug("rejected internal client");
        errors.write(
            request, response, 401, "UNAUTHORIZED", TenantContextFilter.UNAUTHORIZED_MESSAGE);
        return;
      }
    }
    filterChain.doFilter(request, response);
  }

  static Jwt currentJwt() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication instanceof JwtAuthenticationToken jwtAuth) {
      return jwtAuth.getToken();
    }
    return null;
  }
}
