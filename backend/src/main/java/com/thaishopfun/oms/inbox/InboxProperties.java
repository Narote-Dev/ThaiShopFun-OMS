package com.thaishopfun.oms.inbox;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** HMAC secrets and the inbox poller. Secrets are comma-separated, current key first. */
@ConfigurationProperties(prefix = "oms.inbox")
public class InboxProperties {

  private String hmacSecrets = "";
  private boolean workerEnabled = true;
  private long workerDelayMs = 1000;
  private int batchSize = 5;
  private Duration lease = Duration.ofMinutes(5);
  private Duration handlerTimeout = Duration.ofSeconds(30);
  private Duration suspendDefer = Duration.ofMinutes(5);
  private double jitterRatio = 0.2;
  private Duration deferDelay = Duration.ofSeconds(30);
  private Duration maxDefer = Duration.ofHours(24);

  public List<String> secrets() {
    // Step 1: Split on comma so a rotation window can list the current key and the previous one.
    if (hmacSecrets == null || hmacSecrets.isBlank()) {
      return List.of();
    }
    List<String> parsed = new ArrayList<>();
    for (String part : hmacSecrets.split(",")) {
      String secret = part.trim();
      if (!secret.isEmpty()) {
        parsed.add(secret);
      }
    }
    return List.copyOf(parsed);
  }

  public String getHmacSecrets() {
    return hmacSecrets;
  }

  public void setHmacSecrets(String hmacSecrets) {
    this.hmacSecrets = hmacSecrets;
  }

  public boolean isWorkerEnabled() {
    return workerEnabled;
  }

  public void setWorkerEnabled(boolean workerEnabled) {
    this.workerEnabled = workerEnabled;
  }

  public long getWorkerDelayMs() {
    return workerDelayMs;
  }

  public void setWorkerDelayMs(long workerDelayMs) {
    this.workerDelayMs = workerDelayMs;
  }

  public int getBatchSize() {
    return batchSize;
  }

  public void setBatchSize(int batchSize) {
    this.batchSize = batchSize;
  }

  public Duration getHandlerTimeout() {
    return handlerTimeout;
  }

  public void setHandlerTimeout(Duration handlerTimeout) {
    this.handlerTimeout = handlerTimeout;
  }

  public Duration getLease() {
    return lease;
  }

  public void setLease(Duration lease) {
    this.lease = lease;
  }

  public Duration getSuspendDefer() {
    return suspendDefer;
  }

  public void setSuspendDefer(Duration suspendDefer) {
    this.suspendDefer = suspendDefer;
  }

  public double getJitterRatio() {
    return jitterRatio;
  }

  public void setJitterRatio(double jitterRatio) {
    this.jitterRatio = jitterRatio;
  }

  public Duration getDeferDelay() {
    return deferDelay;
  }

  public void setDeferDelay(Duration deferDelay) {
    this.deferDelay = deferDelay;
  }

  public Duration getMaxDefer() {
    return maxDefer;
  }

  public void setMaxDefer(Duration maxDefer) {
    this.maxDefer = maxDefer;
  }
}
