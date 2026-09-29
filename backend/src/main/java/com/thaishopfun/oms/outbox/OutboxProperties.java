package com.thaishopfun.oms.outbox;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "oms.outbox")
public class OutboxProperties {

  private boolean publisherEnabled = true;
  private long pollDelayMs = 2000;
  private int batchSize = 20;
  private Duration lease = Duration.ofMinutes(5);
  private Duration httpTimeout = Duration.ofSeconds(10);
  private String destinationUrl = "";
  private String webhookSecret = "";
  private double jitterRatio = 0.2;

  public boolean isPublisherEnabled() {
    return publisherEnabled;
  }

  public void setPublisherEnabled(boolean publisherEnabled) {
    this.publisherEnabled = publisherEnabled;
  }

  public long getPollDelayMs() {
    return pollDelayMs;
  }

  public void setPollDelayMs(long pollDelayMs) {
    this.pollDelayMs = pollDelayMs;
  }

  public int getBatchSize() {
    return batchSize;
  }

  public void setBatchSize(int batchSize) {
    this.batchSize = batchSize;
  }

  public Duration getLease() {
    return lease;
  }

  public void setLease(Duration lease) {
    this.lease = lease;
  }

  public Duration getHttpTimeout() {
    return httpTimeout;
  }

  public void setHttpTimeout(Duration httpTimeout) {
    this.httpTimeout = httpTimeout;
  }

  public String getDestinationUrl() {
    return destinationUrl;
  }

  public void setDestinationUrl(String destinationUrl) {
    this.destinationUrl = destinationUrl;
  }

  public String getWebhookSecret() {
    return webhookSecret;
  }

  public void setWebhookSecret(String webhookSecret) {
    this.webhookSecret = webhookSecret;
  }

  public double getJitterRatio() {
    return jitterRatio;
  }

  public void setJitterRatio(double jitterRatio) {
    this.jitterRatio = jitterRatio;
  }

  public boolean destinationConfigured() {
    return destinationUrl != null
        && !destinationUrl.isBlank()
        && webhookSecret != null
        && !webhookSecret.isBlank();
  }

  /**
   * Rejects a destination that must not be claimed. Blank stays idle via {@link
   * #destinationConfigured}.
   */
  public void assertSendable() {
    if (!destinationConfigured()) {
      throw new IllegalStateException("outbox destination is not configured");
    }
    requireAbsoluteHttp(destinationUrl);
    if (webhookSecret.getBytes(StandardCharsets.UTF_8).length < 32) {
      throw new IllegalStateException("outbox webhook secret must be at least 32 bytes");
    }
  }

  /** Called once at startup. A blank URL or secret is idle, not invalid. */
  public void validate() {
    // Step 1: A sub-second lease becomes 0 seconds in claim_outbox_batch and is rejected there.
    if (lease == null || lease.compareTo(Duration.ofSeconds(1)) < 0) {
      throw new IllegalStateException("outbox lease must be at least 1s");
    }
    if (batchSize < 1 || batchSize > 1000) {
      throw new IllegalStateException("outbox batch size must be between 1 and 1000");
    }
    if (httpTimeout == null || httpTimeout.isZero() || httpTimeout.isNegative()) {
      throw new IllegalStateException("outbox http timeout must be positive");
    }
    // Step 2: The whole batch, plus one timeout of margin, must fit inside the lease.
    Duration budget = httpTimeout.multipliedBy((long) batchSize + 1);
    if (lease.compareTo(budget) <= 0) {
      throw new IllegalStateException(
          "outbox lease must be longer than batchSize * httpTimeout plus one timeout of margin");
    }
    if (jitterRatio < 0 || jitterRatio >= 1) {
      throw new IllegalStateException("outbox jitter ratio must be in [0, 1)");
    }
    // Step 3: Only a configured destination is checked. Empty means the publisher stays idle.
    if (destinationUrl != null && !destinationUrl.isBlank()) {
      requireAbsoluteHttp(destinationUrl);
    }
    if (webhookSecret != null && !webhookSecret.isBlank()) {
      int bytes = webhookSecret.getBytes(StandardCharsets.UTF_8).length;
      if (bytes < 32) {
        throw new IllegalStateException("outbox webhook secret must be at least 32 bytes");
      }
    }
  }

  private static void requireAbsoluteHttp(String url) {
    URI uri;
    try {
      uri = URI.create(url.trim());
    } catch (IllegalArgumentException ex) {
      throw new IllegalStateException("outbox destination URL must be absolute http or https");
    }
    String scheme = uri.getScheme();
    boolean http = "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
    if (!uri.isAbsolute() || uri.getRawAuthority() == null || !http) {
      throw new IllegalStateException("outbox destination URL must be absolute http or https");
    }
  }
}
