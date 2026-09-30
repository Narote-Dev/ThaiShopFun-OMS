package com.thaishopfun.oms.stock;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Reservation engine settings ({@code oms.stock}). Validated once at startup. */
@ConfigurationProperties(prefix = "oms.stock")
public class StockProperties {

  /** Longest expiry tick that still returns every expired hold within the plan's 2 minutes. */
  static final Duration MAX_EXPIRY_INTERVAL = Duration.ofSeconds(60);

  private Duration checkoutTtl = Duration.ofMinutes(15);
  private Duration lockTimeout = Duration.ofSeconds(2);
  private final Retry retry = new Retry();
  private final Expiry expiry = new Expiry();

  public void validate() {
    // Step 1: TTL and lock timeout must be positive. lock_timeout 0 would mean "wait forever".
    requirePositive(checkoutTtl, "oms.stock.checkout-ttl");
    requirePositive(lockTimeout, "oms.stock.lock-timeout");
    // Step 2: Retry is bounded. The brief caps it at 3 retries.
    if (retry.maxRetries < 0 || retry.maxRetries > 3) {
      throw new IllegalStateException("oms.stock.retry.max-retries must be between 0 and 3");
    }
    requirePositive(retry.initialBackoff, "oms.stock.retry.initial-backoff");
    requirePositive(retry.maxBackoff, "oms.stock.retry.max-backoff");
    if (retry.multiplier < 1) {
      throw new IllegalStateException("oms.stock.retry.multiplier must be at least 1");
    }
    // Step 3: The expiry tick keeps the 2 minute return bound (tick + one run).
    if (expiry.intervalMs < 1 || expiry.intervalMs > MAX_EXPIRY_INTERVAL.toMillis()) {
      throw new IllegalStateException("oms.stock.expiry.interval-ms must be between 1 and 60000");
    }
    if (expiry.batchSize < 1 || expiry.batchSize > 1000) {
      throw new IllegalStateException("oms.stock.expiry.batch-size must be between 1 and 1000");
    }
    if (expiry.tenantLimit < 1 || expiry.tenantLimit > 1000) {
      throw new IllegalStateException("oms.stock.expiry.tenant-limit must be between 1 and 1000");
    }
    if (expiry.maxBatchesPerTenant < 1) {
      throw new IllegalStateException("oms.stock.expiry.max-batches-per-tenant must be positive");
    }
  }

  private static void requirePositive(Duration value, String name) {
    if (value == null || value.isZero() || value.isNegative()) {
      throw new IllegalStateException(name + " must be positive");
    }
  }

  public Duration getCheckoutTtl() {
    return checkoutTtl;
  }

  public void setCheckoutTtl(Duration checkoutTtl) {
    this.checkoutTtl = checkoutTtl;
  }

  public Duration getLockTimeout() {
    return lockTimeout;
  }

  public void setLockTimeout(Duration lockTimeout) {
    this.lockTimeout = lockTimeout;
  }

  public Retry getRetry() {
    return retry;
  }

  public Expiry getExpiry() {
    return expiry;
  }

  /** Whole-transaction retry on 40P01 and 40001. Delays are 10, 40, 160 ms before jitter. */
  public static class Retry {

    private int maxRetries = 3;
    private Duration initialBackoff = Duration.ofMillis(10);
    private int multiplier = 4;
    private Duration maxBackoff = Duration.ofMillis(160);

    public int getMaxRetries() {
      return maxRetries;
    }

    public void setMaxRetries(int maxRetries) {
      this.maxRetries = maxRetries;
    }

    public Duration getInitialBackoff() {
      return initialBackoff;
    }

    public void setInitialBackoff(Duration initialBackoff) {
      this.initialBackoff = initialBackoff;
    }

    public int getMultiplier() {
      return multiplier;
    }

    public void setMultiplier(int multiplier) {
      this.multiplier = multiplier;
    }

    public Duration getMaxBackoff() {
      return maxBackoff;
    }

    public void setMaxBackoff(Duration maxBackoff) {
      this.maxBackoff = maxBackoff;
    }
  }

  /** Expiry job. Tests set {@code enabled=false} and call {@link StockExpiryJob} directly. */
  public static class Expiry {

    private boolean enabled = true;
    private long intervalMs = 60_000;
    private int batchSize = 200;
    private int tenantLimit = 100;
    private int maxBatchesPerTenant = 50;

    public boolean isEnabled() {
      return enabled;
    }

    public void setEnabled(boolean enabled) {
      this.enabled = enabled;
    }

    public long getIntervalMs() {
      return intervalMs;
    }

    public void setIntervalMs(long intervalMs) {
      this.intervalMs = intervalMs;
    }

    public int getBatchSize() {
      return batchSize;
    }

    public void setBatchSize(int batchSize) {
      this.batchSize = batchSize;
    }

    public int getTenantLimit() {
      return tenantLimit;
    }

    public void setTenantLimit(int tenantLimit) {
      this.tenantLimit = tenantLimit;
    }

    public int getMaxBatchesPerTenant() {
      return maxBatchesPerTenant;
    }

    public void setMaxBatchesPerTenant(int maxBatchesPerTenant) {
      this.maxBatchesPerTenant = maxBatchesPerTenant;
    }
  }
}
