package com.thaishopfun.oms.channel.exception;

/** Transient upstream failure (5xx, timeout, circuit open). */
public class ChannelUnavailableException extends RuntimeException {

  public ChannelUnavailableException(String message) {
    super(message);
  }

  public ChannelUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }
}
