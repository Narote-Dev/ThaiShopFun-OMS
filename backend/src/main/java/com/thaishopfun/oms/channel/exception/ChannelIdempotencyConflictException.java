package com.thaishopfun.oms.channel.exception;

/** HTTP 409 when the same idempotency key was reused with a different body. */
public class ChannelIdempotencyConflictException extends RuntimeException {

  public ChannelIdempotencyConflictException(String message) {
    super(message);
  }
}
