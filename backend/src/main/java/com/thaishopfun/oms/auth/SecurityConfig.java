package com.thaishopfun.oms.auth;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
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
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableConfigurationProperties(OmsSecurityProperties.class)
public class SecurityConfig {

  @Bean
  JwtDecoder jwtDecoder(OmsSecurityProperties properties) {
    // Step 1: Local boot has no IdP yet. Reject tokens instead of failing startup.
    if (properties.getJwksUri() == null || properties.getJwksUri().isBlank()) {
      return new RejectingJwtDecoder();
    }
    if (properties.getAudience().equals(properties.getInternalAudience())) {
      throw new IllegalStateException("oms.security.audience and internal-audience must differ");
    }
    NimbusJwtDecoder decoder =
        NimbusJwtDecoder.withJwkSetUri(properties.getJwksUri())
            .jwsAlgorithm(SignatureAlgorithm.RS256)
            .build();
    // Step 1: Pass typ ourselves. createDefaultWithIssuer inserts JwtTypeValidator.jwt(), which
    // accepts only JWT and would ignore oms.security.accepted-token-types.
    decoder.setJwtValidator(
        JwtValidators.createDefaultWithValidators(
            List.of(
                new JwtIssuerValidator(properties.getIssuer()),
                tokenTypes(properties.getAcceptedTokenTypes()),
                new AudienceValidator(
                    properties.getAudience(), properties.getInternalAudience()))));
    return decoder;
  }

  @Bean
  @Order(1)
  SecurityFilterChain internalChain(
      HttpSecurity http, JwtDecoder jwtDecoder, ApiErrors errors, OmsSecurityProperties properties)
      throws Exception {
    stateless(http, errors);
    http.securityMatcher("/internal/**");
    http.authorizeHttpRequests(auth -> auth.anyRequest().authenticated());
    oauth(http, jwtDecoder, errors);
    http.addFilterAfter(
        new RequiredAudienceFilter(
            errors, properties.getInternalAudience(), properties.getInternalClientIds()),
        BearerTokenAuthenticationFilter.class);
    return http.build();
  }

  @Bean
  @Order(2)
  SecurityFilterChain apiChain(
      HttpSecurity http,
      JwtDecoder jwtDecoder,
      ApiErrors errors,
      OmsSecurityProperties properties,
      IdentityProvisioner provisioner,
      TenantSessionService sessions,
      EntitlementGate gate)
      throws Exception {
    stateless(http, errors);
    http.securityMatcher("/api/**");
    http.authorizeHttpRequests(auth -> auth.anyRequest().authenticated());
    oauth(http, jwtDecoder, errors);
    http.addFilterAfter(
        new TenantContextFilter(provisioner, sessions, gate, errors, properties.getAudience()),
        BearerTokenAuthenticationFilter.class);
    return http.build();
  }

  @Bean
  @Order(3)
  SecurityFilterChain publicChain(HttpSecurity http, ApiErrors errors) throws Exception {
    stateless(http, errors);
    http.securityMatcher("/**");
    http.authorizeHttpRequests(
        auth -> auth.requestMatchers("/actuator/**", "/error").permitAll().anyRequest().denyAll());
    return http.build();
  }

  private static void stateless(HttpSecurity http, ApiErrors errors) throws Exception {
    http.csrf(AbstractHttpConfigurer::disable)
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .exceptionHandling(
            handler ->
                handler
                    .authenticationEntryPoint(errors::unauthorized)
                    .accessDeniedHandler(errors::forbidden));
  }

  private static void oauth(HttpSecurity http, JwtDecoder jwtDecoder, ApiErrors errors)
      throws Exception {
    http.oauth2ResourceServer(
        oauth2 ->
            oauth2
                .authenticationEntryPoint(errors::unauthorized)
                .accessDeniedHandler(errors::forbidden)
                .jwt(jwt -> jwt.decoder(jwtDecoder)));
  }

  /**
   * Named entries are allowed {@code typ} values. A blank entry allows a missing {@code typ}.
   * Audience still rejects an {@code id_token}.
   */
  private static JwtTypeValidator tokenTypes(List<String> configured) {
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
  private static final class AudienceValidator implements OAuth2TokenValidator<Jwt> {

    private final String audience;
    private final String internalAudience;

    private AudienceValidator(String audience, String internalAudience) {
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
