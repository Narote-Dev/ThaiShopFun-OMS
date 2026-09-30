package com.thaishopfun.oms.stock;

/**
 * A business failure. Thrown after the transaction that stored it under the idempotency key has
 * committed, so a replay with the same key throws the same code.
 */
public class StockOperationException extends RuntimeException {

  private final StockError error;

  public StockOperationException(StockError error, String message) {
    super(error.name() + ": " + message);
    this.error = error;
  }

  public StockError error() {
    return error;
  }
}
