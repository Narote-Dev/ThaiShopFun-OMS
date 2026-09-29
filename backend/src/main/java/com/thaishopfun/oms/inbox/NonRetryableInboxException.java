package com.thaishopfun.oms.inbox;

/** A handler failure that must not be retried. The worker marks the row DEAD. */
public class NonRetryableInboxException extends RuntimeException {

  public NonRetryableInboxException(String message) {
    super(message);
  }
}
