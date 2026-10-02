package com.thaishopfun.oms.channel.exception;

/** HTTP 5xx (except 502/503/504) — counts toward the circuit breaker and is not retried. */
public class ChannelServerErrorException extends RuntimeException {

  private final int status;
  private final String errorCode;
  private final String traceId;

  public ChannelServerErrorException(int status, String errorCode, String message, String traceId) {
    super(message);
    this.status = status;
    this.errorCode = errorCode;
    this.traceId = traceId;
  }

  public ChannelServerErrorException(int status, String errorCode, String message) {
    this(status, errorCode, message, null);
  }

  public int status() {
    return status;
  }

  public String errorCode() {
    return errorCode;
  }

  public String traceId() {
    return traceId;
  }
}
