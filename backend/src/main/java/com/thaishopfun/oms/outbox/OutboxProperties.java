package com.thaishopfun.oms.outbox;

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
}
