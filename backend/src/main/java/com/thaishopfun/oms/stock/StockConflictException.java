package com.thaishopfun.oms.stock;

/**
 * A conditional inventory UPDATE matched fewer rows than it locked. Under the engine's own row
 * locks this means a writer skipped the lock order. The transaction is rolled back and retried
 * whole.
 */
class StockConflictException extends RuntimeException {

  StockConflictException(String message) {
    super(message);
  }
}
