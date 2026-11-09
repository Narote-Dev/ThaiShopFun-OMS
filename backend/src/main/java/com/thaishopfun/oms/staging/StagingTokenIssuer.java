package com.thaishopfun.oms.staging;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.thaishopfun.oms.auth.OmsSecurityProperties;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Staging-only RS256 access tokens with the same section 4.1 claim shape as mock TSF. */
@Component
@Profile("staging")
class StagingTokenIssuer {

  static final String ACCESS_TOKEN_TYPE = "at+jwt";

  private final OmsSecurityProperties security;
  private final StagingProperties staging;
  private final StagingSigningKeys keys;

  StagingTokenIssuer(
      OmsSecurityProperties security, StagingProperties staging, StagingSigningKeys keys) {
    this.security = security;
    this.staging = staging;
    this.keys = keys;
  }

  String userAccessToken(StagingDemoAccount account, Instant expiresAt) {
    Instant now = Instant.now();
    Map<String, Object> membership = new LinkedHashMap<>();
    membership.put("tier", account.tier());
    membership.put("status", account.status());
    if (expiresAt != null) {
      membership.put("expires_at", expiresAt.toString());
    }
    JWTClaimsSet claims =
        new JWTClaimsSet.Builder()
            .issuer(security.getIssuer())
            .audience(security.getAudience())
            .subject(account.tsfUserId())
            .issueTime(Date.from(now))
            .expirationTime(Date.from(now.plusSeconds(staging.getAccessTokenSeconds())))
            .jwtID(UUID.randomUUID().toString())
            .claim("email", account.email())
            .claim("tsf_shop_id", account.tsfShopId())
            .claim("shop_role", account.shopRole())
            .claim("shop_name", account.shopName())
            .claim("membership", membership)
            .claim("entitlements", List.of("oms"))
            .claim("ent_ver", account.entVer())
            .build();
    return sign(claims);
  }

  private String sign(JWTClaimsSet claims) {
    try {
      SignedJWT jwt =
          new SignedJWT(
              new JWSHeader.Builder(JWSAlgorithm.RS256)
                  .keyID(keys.key().getKeyID())
                  .type(new JOSEObjectType(ACCESS_TOKEN_TYPE))
                  .build(),
              claims);
      jwt.sign(new RSASSASigner(keys.key()));
      return jwt.serialize();
    } catch (Exception ex) {
      throw new IllegalStateException("JWT signing failed", ex);
    }
  }
}
