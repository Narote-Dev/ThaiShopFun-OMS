package com.thaishopfun.oms.order;

/** {@code sales_order.version} did not match on update; the inbox worker may retry the row. */
public class OrderOptimisticLockException extends RuntimeException {

  public OrderOptimisticLockException(String message) {
    super(message);
  }
}
