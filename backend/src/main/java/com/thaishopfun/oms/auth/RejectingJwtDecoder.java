package com.thaishopfun.oms.auth;

import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

/** Used when no JWKS URI is configured. The process still serves {@code /actuator/health}. */
final class RejectingJwtDecoder implements JwtDecoder {

  @Override
  public Jwt decode(String token) {
    throw new BadJwtException("JWKS is not configured");
  }
}
