package com.thaishopfun.oms.outbox;

/**
 * Simulates a process kill. The publisher must not catch this and rewrite the row: the lease stays
 * until it expires, then another instance claims the event.
 */
public class OutboxCrash extends RuntimeException {

  public OutboxCrash(String message) {
    super(message);
  }
}
