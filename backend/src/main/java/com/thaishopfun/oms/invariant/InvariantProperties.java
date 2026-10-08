package com.thaishopfun.oms.invariant;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "oms.invariant")
public class InvariantProperties {

  /** Nightly invariant sweep. Tests set false and call {@link InvariantJob} directly. */
  private boolean enabled = true;

  /** Cron in {@link #zone} (default 02:30 Asia/Bangkok). */
  private String cron = "0 30 2 * * *";

  private String zone = "Asia/Bangkok";

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public String getCron() {
    return cron;
  }

  public void setCron(String cron) {
    this.cron = cron;
  }

  public String getZone() {
    return zone;
  }

  public void setZone(String zone) {
    this.zone = zone;
  }
}
