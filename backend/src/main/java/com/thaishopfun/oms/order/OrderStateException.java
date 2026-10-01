package com.thaishopfun.oms.order;

/** A status transition was rejected by {@link OrderStateMachine} before any database write. */
public class OrderStateException extends RuntimeException {

  public OrderStateException(String message) {
    super(message);
  }
}
