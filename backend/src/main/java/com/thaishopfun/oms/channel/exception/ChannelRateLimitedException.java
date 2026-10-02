package com.thaishopfun.oms.channel.exception;

/** HTTP 429 or adapter-side throttle. Honor {@link #retryAfterSeconds()} when present. */
public class ChannelRateLimitedException extends RuntimeException {

  private final Integer retryAfterSeconds;

  public ChannelRateLimitedException(String message) {
    this(message, null);
  }

  public ChannelRateLimitedException(String message, Integer retryAfterSeconds) {
    super(message);
    this.retryAfterSeconds = retryAfterSeconds;
  }

  public Integer retryAfterSeconds() {
    return retryAfterSeconds;
  }
}
