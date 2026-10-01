package com.thaishopfun.oms.channel.exception;

/** Non-retryable client error from the channel API (4xx except 409 and 429). */
public class ChannelClientException extends RuntimeException {

  private final int statusCode;
  private final String errorCode;
  private final String traceId;

  public ChannelClientException(int statusCode, String errorCode, String message) {
    this(statusCode, errorCode, message, null);
  }

  public ChannelClientException(int statusCode, String errorCode, String message, String traceId) {
    super(message);
    this.statusCode = statusCode;
    this.errorCode = errorCode;
    this.traceId = traceId;
  }

  public int statusCode() {
    return statusCode;
  }

  public String errorCode() {
    return errorCode;
  }

  public String traceId() {
    return traceId;
  }
}
