package com.thaishopfun.oms.stock;

import java.util.UUID;

/**
 * A business failure. Thrown after the transaction that stored it under the idempotency key has
 * committed, so a replay with the same key throws the same code.
 */
public class StockOperationException extends RuntimeException {

  private final StockError error;
  private final UUID skuId;

  public StockOperationException(StockError error, String message) {
    this(error, message, null);
  }

  public StockOperationException(StockError error, String message, UUID skuId) {
    super(error.name() + ": " + message);
    this.error = error;
    this.skuId = skuId;
  }

  public StockError error() {
    return error;
  }

  /** Set for {@link StockError#UNKNOWN_SKU}; null for other errors and legacy stored failures. */
  public UUID skuId() {
    return skuId;
  }
}
