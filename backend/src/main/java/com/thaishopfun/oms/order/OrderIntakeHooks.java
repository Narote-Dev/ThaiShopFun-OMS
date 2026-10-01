package com.thaishopfun.oms.order;

/**
 * Test seam after the outbox append in order intake. Production registers a no-op bean; tests
 * override it to fail the inbox transaction (atomicity AC).
 */
public class OrderIntakeHooks {

  public void afterOutbox() {}
}
