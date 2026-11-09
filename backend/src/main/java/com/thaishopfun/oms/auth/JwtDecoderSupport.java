package com.thaishopfun.oms.auth;

import java.util.ArrayList;
import java.util.List;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTypeValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/** Builds {@link JwtDecoder} instances with shared OMS audience and typ rules. */
final class JwtDecoderSupport {

  private JwtDecoderSupport() {}

  static JwtDecoder singleIssuerDecoder(OmsSecurityProperties properties) {
    // Step 1: Local boot has no IdP yet. Reject tokens instead of failing startup.
    if (properties.getJwksUri() == null || properties.getJwksUri().isBlank()) {
      return new RejectingJwtDecoder();
    }
    validateAudiences(properties);
    return nimbusDecoder(
        properties.getJwksUri(),
        properties.getIssuer(),
        properties.getAudience(),
        properties.getInternalAudience(),
        properties.getAcceptedTokenTypes());
  }

  static JwtDecoder nimbusDecoder(
      String jwksUri,
      String issuer,
      String audience,
      String internalAudience,
      List<String> acceptedTokenTypes) {
    NimbusJwtDecoder decoder =
        NimbusJwtDecoder.withJwkSetUri(jwksUri).jwsAlgorithm(SignatureAlgorithm.RS256).build();
    decoder.setJwtValidator(
        JwtValidators.createDefaultWithValidators(
            List.of(
                new JwtIssuerValidator(issuer),
                tokenTypes(acceptedTokenTypes),
                new AudienceValidator(audience, internalAudience))));
    return decoder;
  }

  static void validateAudiences(OmsSecurityProperties properties) {
    if (properties.getAudience().equals(properties.getInternalAudience())) {
      throw new IllegalStateException("oms.security.audience and internal-audience must differ");
    }
  }

  /**
   * Named entries are allowed {@code typ} values. A blank entry allows a missing {@code typ}.
   * Audience still rejects an {@code id_token}.
   */
  static JwtTypeValidator tokenTypes(List<String> configured) {
    if (configured == null || configured.isEmpty()) {
      throw new IllegalStateException("oms.security.accepted-token-types must not be empty");
    }
    boolean allowMissing = false;
    List<String> types = new ArrayList<>();
    for (String value : configured) {
      if (value == null || value.isBlank()) {
        allowMissing = true;
      } else if (!types.contains(value)) {
        types.add(value);
      }
    }
    if (types.isEmpty()) {
      throw new IllegalStateException(
          "oms.security.accepted-token-types must name at least one typ");
    }
    JwtTypeValidator validator = new JwtTypeValidator(types);
    validator.setAllowEmpty(allowMissing);
    return validator;
  }

  /** Accepts either the user audience or the internal audience. The chain then narrows it. */
  static final class AudienceValidator implements OAuth2TokenValidator<Jwt> {

    private final String audience;
    private final String internalAudience;

    AudienceValidator(String audience, String internalAudience) {
      this.audience = audience;
      this.internalAudience = internalAudience;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
      // Step 1: Exactly one of the two audiences. A token that carries both is rejected.
      List<String> aud = token.getAudience();
      boolean user = aud != null && aud.contains(audience);
      boolean internal = aud != null && aud.contains(internalAudience);
      if (user ^ internal) {
        return OAuth2TokenValidatorResult.success();
      }
      return OAuth2TokenValidatorResult.failure(
          new OAuth2Error("invalid_token", "The required audience is missing", null));
    }
  }
}
