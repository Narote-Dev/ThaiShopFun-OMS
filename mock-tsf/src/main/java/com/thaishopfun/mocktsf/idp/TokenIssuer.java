package com.thaishopfun.mocktsf.idp;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.thaishopfun.mocktsf.MockProperties;
import com.thaishopfun.mocktsf.SeedData;
import com.thaishopfun.mocktsf.SigningKeys;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * RS256 access tokens. User tokens carry the section 4.1 claims. Service tokens carry {@code azp}.
 */
@Component
public class TokenIssuer {

  public static final String USER_AUDIENCE = "oms";
  public static final String OMS_INTERNAL_AUDIENCE = "oms-internal";
  public static final String TSF_INTERNAL_AUDIENCE = "tsf-internal";
  public static final String ACCESS_TOKEN_TYPE = "at+jwt";

  private final MockProperties properties;
  private final SigningKeys keys;

  public TokenIssuer(MockProperties properties, SigningKeys keys) {
    this.properties = properties;
    this.keys = keys;
  }

  public String userAccessToken(SeedData.ShopUser user) {
    Instant now = Instant.now();
    Instant exp = now.plusSeconds(properties.getAccessTokenSeconds());
    Map<String, Object> membership = new LinkedHashMap<>();
    membership.put("tier", user.tier());
    membership.put("status", user.status());
    membership.put("expires_at", user.expiresAt().toString());
    JWTClaimsSet claims =
        new JWTClaimsSet.Builder()
            .issuer(properties.getIssuer())
            .audience(USER_AUDIENCE)
            .subject(user.userId())
            .issueTime(Date.from(now))
            .expirationTime(Date.from(exp))
            .jwtID(UUID.randomUUID().toString())
            .claim("email", user.email())
            .claim("tsf_shop_id", user.shopId())
            .claim("shop_role", user.role())
            .claim("shop_name", user.shopName())
            .claim("membership", membership)
            .claim("entitlements", user.entitlements())
            .claim("ent_ver", user.entVer())
            .build();
    return sign(claims, true);
  }

  /**
   * Minimal OIDC {@code id_token}. {@code aud} is the OIDC client id. The API access token stays
   * the section 4.1 JWT.
   */
  public String idToken(SeedData.ShopUser user, String clientId, String nonce) {
    Instant now = Instant.now();
    Instant exp = now.plusSeconds(properties.getAccessTokenSeconds());
    JWTClaimsSet.Builder claims =
        new JWTClaimsSet.Builder()
            .issuer(properties.getIssuer())
            .audience(clientId)
            .subject(user.userId())
            .issueTime(Date.from(now))
            .expirationTime(Date.from(exp));
    if (nonce != null && !nonce.isBlank()) {
      claims.claim("nonce", nonce);
    }
    return sign(claims.build(), false);
  }

  /** Token this mock presents to OMS {@code /internal/**}. */
  public String tsfServiceToken() {
    return serviceToken(properties.getTsfClientId(), OMS_INTERNAL_AUDIENCE);
  }

  /** Token OMS presents to this mock's section 4.7 API. */
  public String omsServiceToken() {
    return serviceToken(properties.getOmsServiceClientId(), TSF_INTERNAL_AUDIENCE);
  }

  public String serviceToken(String clientId, String audience) {
    Instant now = Instant.now();
    Instant exp = now.plusSeconds(properties.getAccessTokenSeconds());
    JWTClaimsSet claims =
        new JWTClaimsSet.Builder()
            .issuer(properties.getIssuer())
            .audience(audience)
            .subject(clientId)
            .issueTime(Date.from(now))
            .expirationTime(Date.from(exp))
            .jwtID(UUID.randomUUID().toString())
            .claim("azp", clientId)
            .claim("client_id", clientId)
            .build();
    return sign(claims, true);
  }

  private String sign(JWTClaimsSet claims, boolean accessToken) {
    try {
      // Step 1: Access tokens use typ at+jwt. The id_token stays a plain JWT so OMS can reject it.
      SignedJWT jwt =
          new SignedJWT(
              new JWSHeader.Builder(JWSAlgorithm.RS256)
                  .keyID(keys.key().getKeyID())
                  .type(accessToken ? new JOSEObjectType(ACCESS_TOKEN_TYPE) : JOSEObjectType.JWT)
                  .build(),
              claims);
      jwt.sign(new RSASSASigner(keys.key()));
      return jwt.serialize();
    } catch (Exception ex) {
      throw new IllegalStateException("JWT signing failed", ex);
    }
  }
}
