package com.thaishopfun.oms.auth;

import java.util.ArrayList;
import java.util.List;
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

  /**
   * {@code azp} or {@code client_id} values accepted on {@code /internal/**}. Empty rejects all.
   */
  private List<String> internalClientIds = new ArrayList<>();

  /**
   * Access-token {@code typ} values. A blank entry accepts a missing {@code typ}. {@code id_token}
   * is still rejected because its audience is not {@code oms}.
   */
  private List<String> acceptedTokenTypes = new ArrayList<>(List.of("at+jwt", "JWT", ""));

  /**
   * When false, startup fails if {@code current_user} is superuser or has {@code BYPASSRLS}.
   * Default false. Do not enable it for local Docker or tests.
   */
  private boolean allowRlsBypass = false;

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

  public List<String> getInternalClientIds() {
    return internalClientIds;
  }

  public void setInternalClientIds(List<String> internalClientIds) {
    this.internalClientIds = internalClientIds == null ? new ArrayList<>() : internalClientIds;
  }

  public List<String> getAcceptedTokenTypes() {
    return acceptedTokenTypes;
  }

  public void setAcceptedTokenTypes(List<String> acceptedTokenTypes) {
    this.acceptedTokenTypes = acceptedTokenTypes == null ? new ArrayList<>() : acceptedTokenTypes;
  }

  public boolean isAllowRlsBypass() {
    return allowRlsBypass;
  }

  public void setAllowRlsBypass(boolean allowRlsBypass) {
    this.allowRlsBypass = allowRlsBypass;
  }
}
