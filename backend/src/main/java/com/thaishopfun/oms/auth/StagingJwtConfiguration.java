package com.thaishopfun.oms.auth;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.oauth2.jwt.JwtDecoder;

/** Staging trusts the demo IdP for users and mock TSF for service tokens. */
@Configuration
@Profile("staging")
class StagingJwtConfiguration {

  @Bean
  JwtDecoder jwtDecoder(OmsSecurityProperties properties) {
    // Step 1: Require both issuer/JWKS pairs so routing never falls back to a single IdP.
    JwtDecoderSupport.validateAudiences(properties);
    String userJwks = properties.getJwksUri();
    String serviceJwks = properties.getServiceJwksUri();
    String userIssuer = properties.getIssuer();
    String serviceIssuer = properties.getServiceIssuer();
    if (userJwks == null || userJwks.isBlank()) {
      throw new IllegalStateException("oms.security.jwks-uri is required in staging");
    }
    if (serviceJwks == null || serviceJwks.isBlank()) {
      throw new IllegalStateException(
          "oms.security.service-jwks-uri (OMS_SERVICE_JWKS_URI) is required in staging");
    }
    if (serviceIssuer == null || serviceIssuer.isBlank()) {
      throw new IllegalStateException(
          "oms.security.service-issuer (OMS_SERVICE_JWT_ISSUER) is required in staging");
    }
    if (userIssuer.equals(serviceIssuer)) {
      throw new IllegalStateException("user and service JWT issuers must differ in staging");
    }
    // Step 2: Build one decoder per allowlisted issuer.
    Map<String, JwtDecoder> decoders = new LinkedHashMap<>();
    decoders.put(
        userIssuer,
        JwtDecoderSupport.nimbusDecoder(
            userJwks,
            userIssuer,
            properties.getAudience(),
            properties.getInternalAudience(),
            properties.getAcceptedTokenTypes()));
    decoders.put(
        serviceIssuer,
        JwtDecoderSupport.nimbusDecoder(
            serviceJwks,
            serviceIssuer,
            properties.getAudience(),
            properties.getInternalAudience(),
            properties.getAcceptedTokenTypes()));
    return new IssuerRoutingJwtDecoder(decoders);
  }
}
