package com.thaishopfun.oms.channel;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "oms.channel")
public class ChannelProperties {

  private TsfChannelSettings tsf = new TsfChannelSettings();

  public TsfChannelSettings getTsf() {
    return tsf;
  }

  public void setTsf(TsfChannelSettings tsf) {
    this.tsf = tsf == null ? new TsfChannelSettings() : tsf;
  }

  public TsfChannelSettings settingsFor(Channel channel) {
    if (channel == Channel.TSF) {
      return tsf;
    }
    return tsf;
  }

  /** Per-channel resilience defaults (section 4.7 / T16). Bound from {@code oms.channel.tsf.*}. */
  public static class TsfChannelSettings {

    private Duration maxRetryAfter = Duration.ofSeconds(60);

    /** Total wall-time budget for one adapter invocation (waits, HTTP, token, backoff). */
    private Duration callTimeBudget = Duration.ofSeconds(10);

    /** Per HTTP/token request timeout cap (also bounded by remaining call budget). */
    private Duration httpTimeout = Duration.ofSeconds(10);

    private int rateLimitPerSecond = 10;
    private Duration rateLimitWait = Duration.ofSeconds(10);
    private int retryMaxAttempts = 4;
    private Duration retryWaitBase = Duration.ofMillis(500);
    private Duration retryWaitMax = Duration.ofSeconds(30);
    private float circuitFailureRateThreshold = 50f;
    private int circuitSlidingWindowSize = 10;
    private int circuitMinimumNumberOfCalls = 5;
    private Duration circuitWaitInOpenState = Duration.ofSeconds(30);
    private int circuitPermittedCallsInHalfOpen = 3;
    private int bulkheadMaxConcurrent = 8;
    private Duration bulkheadMaxWait = Duration.ofSeconds(10);
    private int listingsPageLimit = 100;

    public Duration getMaxRetryAfter() {
      return maxRetryAfter;
    }

    public void setMaxRetryAfter(Duration maxRetryAfter) {
      this.maxRetryAfter = maxRetryAfter;
    }

    public Duration getCallTimeBudget() {
      return callTimeBudget;
    }

    public void setCallTimeBudget(Duration callTimeBudget) {
      this.callTimeBudget = callTimeBudget;
    }

    public Duration getHttpTimeout() {
      return httpTimeout;
    }

    public void setHttpTimeout(Duration httpTimeout) {
      this.httpTimeout = httpTimeout;
    }

    public int getRateLimitPerSecond() {
      return rateLimitPerSecond;
    }

    public void setRateLimitPerSecond(int rateLimitPerSecond) {
      this.rateLimitPerSecond = rateLimitPerSecond;
    }

    public Duration getRateLimitWait() {
      return rateLimitWait;
    }

    public void setRateLimitWait(Duration rateLimitWait) {
      this.rateLimitWait = rateLimitWait;
    }

    public int getRetryMaxAttempts() {
      return retryMaxAttempts;
    }

    public void setRetryMaxAttempts(int retryMaxAttempts) {
      this.retryMaxAttempts = retryMaxAttempts;
    }

    public Duration getRetryWaitBase() {
      return retryWaitBase;
    }

    public void setRetryWaitBase(Duration retryWaitBase) {
      this.retryWaitBase = retryWaitBase;
    }

    public Duration getRetryWaitMax() {
      return retryWaitMax;
    }

    public void setRetryWaitMax(Duration retryWaitMax) {
      this.retryWaitMax = retryWaitMax;
    }

    public float getCircuitFailureRateThreshold() {
      return circuitFailureRateThreshold;
    }

    public void setCircuitFailureRateThreshold(float circuitFailureRateThreshold) {
      this.circuitFailureRateThreshold = circuitFailureRateThreshold;
    }

    public int getCircuitSlidingWindowSize() {
      return circuitSlidingWindowSize;
    }

    public void setCircuitSlidingWindowSize(int circuitSlidingWindowSize) {
      this.circuitSlidingWindowSize = circuitSlidingWindowSize;
    }

    public int getCircuitMinimumNumberOfCalls() {
      return circuitMinimumNumberOfCalls;
    }

    public void setCircuitMinimumNumberOfCalls(int circuitMinimumNumberOfCalls) {
      this.circuitMinimumNumberOfCalls = circuitMinimumNumberOfCalls;
    }

    public Duration getCircuitWaitInOpenState() {
      return circuitWaitInOpenState;
    }

    public void setCircuitWaitInOpenState(Duration circuitWaitInOpenState) {
      this.circuitWaitInOpenState = circuitWaitInOpenState;
    }

    public int getCircuitPermittedCallsInHalfOpen() {
      return circuitPermittedCallsInHalfOpen;
    }

    public void setCircuitPermittedCallsInHalfOpen(int circuitPermittedCallsInHalfOpen) {
      this.circuitPermittedCallsInHalfOpen = circuitPermittedCallsInHalfOpen;
    }

    public int getBulkheadMaxConcurrent() {
      return bulkheadMaxConcurrent;
    }

    public void setBulkheadMaxConcurrent(int bulkheadMaxConcurrent) {
      this.bulkheadMaxConcurrent = bulkheadMaxConcurrent;
    }

    public Duration getBulkheadMaxWait() {
      return bulkheadMaxWait;
    }

    public void setBulkheadMaxWait(Duration bulkheadMaxWait) {
      this.bulkheadMaxWait = bulkheadMaxWait;
    }

    public int getListingsPageLimit() {
      return listingsPageLimit;
    }

    public void setListingsPageLimit(int listingsPageLimit) {
      this.listingsPageLimit = listingsPageLimit;
    }
  }
}
