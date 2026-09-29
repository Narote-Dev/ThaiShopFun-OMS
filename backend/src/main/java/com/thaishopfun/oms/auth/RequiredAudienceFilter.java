package com.thaishopfun.oms.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

/** Rejects a token whose {@code aud} is not the one this chain requires. Status is 401. */
final class RequiredAudienceFilter extends OncePerRequestFilter {

  private final ApiErrors errors;
  private final String requiredAudience;

  RequiredAudienceFilter(ApiErrors errors, String requiredAudience) {
    this.errors = errors;
    this.requiredAudience = requiredAudience;
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
      errors.write(request, response, 401, "UNAUTHORIZED", "Invalid audience");
      return;
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
