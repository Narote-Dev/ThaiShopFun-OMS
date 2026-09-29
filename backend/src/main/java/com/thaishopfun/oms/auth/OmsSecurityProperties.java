package com.thaishopfun.oms.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Issuer, audiences, and JWKS location. T05 points these at the mock TSF IdP. */
@ConfigurationProperties(prefix = "oms.security")
public class OmsSecurityProperties {

  /** Expected {@code iss}. Tokens from any other issuer are rejected. */
  private String issuer = "http://localhost:9/tsf-idp";

  /** User access tokens. {@code aud} must be this value on {@code /api/**}. */
  private String audience = "oms";

  /** Client-credentials tokens. {@code aud} must be this value on {@code /internal/**}. */
  private String internalAudience = "oms-internal";

  /**
   * JWKS URI. Blank keeps the process up: API calls return 401 and the actuator stays open so a
   * local boot does not need the IdP.
   */
  private String jwksUri = "";

  public String getIssuer() {
    return issuer;
  }

  public void setIssuer(String issuer) {
    this.issuer = issuer;
  }

  public String getAudience() {
    return audience;
  }

  public void setAudience(String audience) {
    this.audience = audience;
  }

  public String getInternalAudience() {
    return internalAudience;
  }

  public void setInternalAudience(String internalAudience) {
    this.internalAudience = internalAudience;
  }

  public String getJwksUri() {
    return jwksUri;
  }

  public void setJwksUri(String jwksUri) {
    this.jwksUri = jwksUri;
  }
}
