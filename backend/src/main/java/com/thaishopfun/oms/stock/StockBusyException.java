package com.thaishopfun.oms.stock;

/**
 * Deadlock or serialization retries were exhausted, or a lock wait hit {@code lock_timeout}.
 * Nothing was committed. The caller may try again later.
 */
public class StockBusyException extends RuntimeException {

  private final String reason;

  public StockBusyException(String reason, Throwable cause) {
    super("stock is busy: " + reason, cause);
    this.reason = reason;
  }

  public String reason() {
    return reason;
  }
}
