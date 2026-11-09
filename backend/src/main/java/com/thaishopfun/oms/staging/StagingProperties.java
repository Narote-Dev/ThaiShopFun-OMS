package com.thaishopfun.oms.staging;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "oms.staging")
public class StagingProperties {

  private String idpBasePath = "/staging-idp";
  private long accessTokenSeconds = 3600;
  private String demoAccounts = "";
  private boolean demoSeedOnStartup = false;
  private boolean adminSeedDemo = false;
  private String corsAllowedOrigins = "";
  private String jwtSigningKeyPem = "";
  private String jwtKeyId = "staging-oms-1";
  private int loginMaxAttempts = 30;
  private long loginWindowSeconds = 900;
  private int loginLockoutThreshold = 5;
  private long loginLockoutSeconds = 900;

  public String getIdpBasePath() {
    return idpBasePath;
  }

  public void setIdpBasePath(String idpBasePath) {
    this.idpBasePath = idpBasePath;
  }

  public long getAccessTokenSeconds() {
    return accessTokenSeconds;
  }

  public void setAccessTokenSeconds(long accessTokenSeconds) {
    this.accessTokenSeconds = accessTokenSeconds;
  }

  public String getDemoAccounts() {
    return demoAccounts;
  }

  public void setDemoAccounts(String demoAccounts) {
    this.demoAccounts = demoAccounts;
  }

  public boolean isDemoSeedOnStartup() {
    return demoSeedOnStartup;
  }

  public void setDemoSeedOnStartup(boolean demoSeedOnStartup) {
    this.demoSeedOnStartup = demoSeedOnStartup;
  }

  public boolean isAdminSeedDemo() {
    return adminSeedDemo;
  }

  public void setAdminSeedDemo(boolean adminSeedDemo) {
    this.adminSeedDemo = adminSeedDemo;
  }

  public String getCorsAllowedOrigins() {
    return corsAllowedOrigins;
  }

  public void setCorsAllowedOrigins(String corsAllowedOrigins) {
    this.corsAllowedOrigins = corsAllowedOrigins;
  }

  public List<String> corsOrigins() {
    return splitCsv(corsAllowedOrigins);
  }

  public String getJwtSigningKeyPem() {
    return jwtSigningKeyPem;
  }

  public void setJwtSigningKeyPem(String jwtSigningKeyPem) {
    this.jwtSigningKeyPem = jwtSigningKeyPem;
  }

  public String getJwtKeyId() {
    return jwtKeyId;
  }

  public void setJwtKeyId(String jwtKeyId) {
    this.jwtKeyId = jwtKeyId;
  }

  public int getLoginMaxAttempts() {
    return loginMaxAttempts;
  }

  public void setLoginMaxAttempts(int loginMaxAttempts) {
    this.loginMaxAttempts = loginMaxAttempts;
  }

  public long getLoginWindowSeconds() {
    return loginWindowSeconds;
  }

  public void setLoginWindowSeconds(long loginWindowSeconds) {
    this.loginWindowSeconds = loginWindowSeconds;
  }

  public int getLoginLockoutThreshold() {
    return loginLockoutThreshold;
  }

  public void setLoginLockoutThreshold(int loginLockoutThreshold) {
    this.loginLockoutThreshold = loginLockoutThreshold;
  }

  public long getLoginLockoutSeconds() {
    return loginLockoutSeconds;
  }

  public void setLoginLockoutSeconds(long loginLockoutSeconds) {
    this.loginLockoutSeconds = loginLockoutSeconds;
  }

  private static List<String> splitCsv(String raw) {
    if (raw == null || raw.isBlank()) {
      return List.of();
    }
    List<String> out = new ArrayList<>();
    for (String part : raw.split(",")) {
      String trimmed = part.trim();
      if (!trimmed.isEmpty()) {
        out.add(trimmed);
      }
    }
    return List.copyOf(out);
  }
}
