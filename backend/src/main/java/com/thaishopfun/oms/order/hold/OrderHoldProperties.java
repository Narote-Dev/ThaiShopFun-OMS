package com.thaishopfun.oms.order.hold;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "oms.order.hold-resolver")
public class OrderHoldProperties {

  private boolean enabled = true;
  private Duration interval = Duration.ofMinutes(1);
  private int batchSize = 50;
  private int reevalCap = 200;
  private int lockRetries = 3;
  private Duration backoffBase = Duration.ofMinutes(1);
  private Duration backoffMax = Duration.ofHours(1);
  private double backoffJitter = 0.2;

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public Duration getInterval() {
    return interval;
  }

  public void setInterval(Duration interval) {
    this.interval = interval;
  }

  public int getBatchSize() {
    return batchSize;
  }

  public void setBatchSize(int batchSize) {
    this.batchSize = batchSize;
  }

  public int getReevalCap() {
    return reevalCap;
  }

  public void setReevalCap(int reevalCap) {
    this.reevalCap = reevalCap;
  }

  public int getLockRetries() {
    return lockRetries;
  }

  public void setLockRetries(int lockRetries) {
    this.lockRetries = lockRetries;
  }

  public Duration getBackoffBase() {
    return backoffBase;
  }

  public void setBackoffBase(Duration backoffBase) {
    this.backoffBase = backoffBase;
  }

  public Duration getBackoffMax() {
    return backoffMax;
  }

  public void setBackoffMax(Duration backoffMax) {
    this.backoffMax = backoffMax;
  }

  public double getBackoffJitter() {
    return backoffJitter;
  }

  public void setBackoffJitter(double backoffJitter) {
    this.backoffJitter = backoffJitter;
  }
}
