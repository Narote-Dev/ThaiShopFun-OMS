package com.thaishopfun.oms.stock;

/**
 * Called inside every engine write transaction once its inventory rows are locked. Production
 * registers a no-op bean. Tests override it to fail a transaction midway or force a retry.
 *
 * <p>Package-private on purpose: this is not a public fault switch.
 */
class StockHooks {

  /** Called after the document is locked and validated, before inventory rows are locked. */
  void beforeInventoryLock(String operation) {}

  void afterInventoryLocked(String operation) {}
}
