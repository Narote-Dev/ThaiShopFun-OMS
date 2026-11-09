package com.thaishopfun.oms.auth;

import com.nimbusds.jwt.JWT;
import com.nimbusds.jwt.JWTParser;
import java.text.ParseException;
import java.util.Map;
import java.util.Set;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

/**
 * Routes a bearer token to a per-issuer {@link JwtDecoder}. Only issuers present in the map are
 * trusted.
 */
final class IssuerRoutingJwtDecoder implements JwtDecoder {

  private final Map<String, JwtDecoder> decodersByIssuer;
  private final Set<String> trustedIssuers;

  IssuerRoutingJwtDecoder(Map<String, JwtDecoder> decodersByIssuer) {
    // Step 1: Copy the allowlist. Unknown issuers never reach a delegate decoder.
    this.decodersByIssuer = Map.copyOf(decodersByIssuer);
    this.trustedIssuers = decodersByIssuer.keySet();
    if (trustedIssuers.isEmpty()) {
      throw new IllegalArgumentException("At least one trusted issuer is required");
    }
  }

  @Override
  public Jwt decode(String token) throws JwtException {
    // Step 1: Read iss from the unsigned JWT envelope. Malformed tokens fail here.
    String issuer = readIssuer(token);
    if (!trustedIssuers.contains(issuer)) {
      throw new BadJwtException("Untrusted token issuer");
    }
    JwtDecoder delegate = decodersByIssuer.get(issuer);
    if (delegate == null) {
      throw new BadJwtException("Untrusted token issuer");
    }
    // Step 2: Delegate signature and claim validation to the issuer-specific decoder.
    return delegate.decode(token);
  }

  private static String readIssuer(String token) {
    try {
      JWT jwt = JWTParser.parse(token);
      String issuer = jwt.getJWTClaimsSet().getIssuer();
      if (issuer == null || issuer.isBlank()) {
        throw new JwtException("Token issuer is missing");
      }
      return issuer;
    } catch (ParseException ex) {
      throw new JwtException("Malformed token", ex);
    }
  }
}
