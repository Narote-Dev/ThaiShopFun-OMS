package com.thaishopfun.mocktsf;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Local IdP, HMAC, and OMS base URL. Values are dev-only. */
@ConfigurationProperties(prefix = "mock")
public class MockProperties {

  private String issuer = "http://localhost:8090/tsf-idp";
  private String omsBaseUrl = "http://localhost:8080";
  private String inboxHmacSecret = "dev-inbox-hmac-secret";
  private String outboxHmacSecret = "dev-outbox-webhook-secret-local-only";
  private String publicClientId = "oms-web";
  private String corsAllowedOrigins = "http://localhost:5173,http://127.0.0.1:5173";
  private String tsfClientId = "tsf";
  private String tsfClientSecret = "dev-tsf-client-secret";
  private String omsServiceClientId = "oms-service";
  private String omsServiceClientSecret = "dev-oms-service-secret";
  private long accessTokenSeconds = 600;

  public String getIssuer() {
    return issuer;
  }

  public void setIssuer(String issuer) {
    this.issuer = issuer;
  }

  public String getOmsBaseUrl() {
    return omsBaseUrl;
  }

  public void setOmsBaseUrl(String omsBaseUrl) {
    this.omsBaseUrl = omsBaseUrl;
  }

  public String getInboxHmacSecret() {
    return inboxHmacSecret;
  }

  public void setInboxHmacSecret(String inboxHmacSecret) {
    this.inboxHmacSecret = inboxHmacSecret;
  }

  public String getOutboxHmacSecret() {
    return outboxHmacSecret;
  }

  public void setOutboxHmacSecret(String outboxHmacSecret) {
    this.outboxHmacSecret = outboxHmacSecret;
  }

  public String getPublicClientId() {
    return publicClientId;
  }

  public void setPublicClientId(String publicClientId) {
    this.publicClientId = publicClientId;
  }

  public String getCorsAllowedOrigins() {
    return corsAllowedOrigins;
  }

  public void setCorsAllowedOrigins(String corsAllowedOrigins) {
    this.corsAllowedOrigins = corsAllowedOrigins;
  }

  public List<String> corsOrigins() {
    return split(corsAllowedOrigins);
  }

  public String getTsfClientId() {
    return tsfClientId;
  }

  public void setTsfClientId(String tsfClientId) {
    this.tsfClientId = tsfClientId;
  }

  public String getTsfClientSecret() {
    return tsfClientSecret;
  }

  public void setTsfClientSecret(String tsfClientSecret) {
    this.tsfClientSecret = tsfClientSecret;
  }

  public String getOmsServiceClientId() {
    return omsServiceClientId;
  }

  public void setOmsServiceClientId(String omsServiceClientId) {
    this.omsServiceClientId = omsServiceClientId;
  }

  public String getOmsServiceClientSecret() {
    return omsServiceClientSecret;
  }

  public void setOmsServiceClientSecret(String omsServiceClientSecret) {
    this.omsServiceClientSecret = omsServiceClientSecret;
  }

  public long getAccessTokenSeconds() {
    return accessTokenSeconds;
  }

  public void setAccessTokenSeconds(long accessTokenSeconds) {
    this.accessTokenSeconds = accessTokenSeconds;
  }

  public List<String> inboxSecrets() {
    return split(inboxHmacSecret);
  }

  public List<String> outboxSecrets() {
    return split(outboxHmacSecret);
  }

  private static List<String> split(String raw) {
    if (raw == null || raw.isBlank()) {
      return List.of();
    }
    List<String> parsed = new ArrayList<>();
    for (String part : raw.split(",")) {
      String secret = part.trim();
      if (!secret.isEmpty()) {
        parsed.add(secret);
      }
    }
    return List.copyOf(parsed);
  }
}
