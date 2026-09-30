package com.thaishopfun.oms.stock;

/**
 * Business outcomes other than success. {@code status} is the HTTP-style code stored in {@code
 * idempotency_key.response_status}. T12A maps the codes to 4.3 responses.
 */
public enum StockError {
  /** Reserve only. Returned as {@link ReserveResult.Status#OUT_OF_STOCK}, never thrown. */
  OUT_OF_STOCK(409),
  NO_DEFAULT_WAREHOUSE(422),
  UNKNOWN_SKU(422),
  UNKNOWN_LISTING(422),
  BUNDLE_WITHOUT_COMPONENTS(422),
  OWNER_ALREADY_RESERVED(409),
  RESERVATION_NOT_FOUND(404),
  RESERVATION_NOT_ACTIVE(409),
  // Change: T08A stock documents. Never stored under a key: the post rolls back and can be retried.
  DOCUMENT_NOT_FOUND(404),
  DOCUMENT_NOT_DRAFT(409),
  DOCUMENT_NOT_POSTED(409),
  DOCUMENT_EMPTY(422),
  INVALID_LINE(422),
  REASON_REQUIRED(422),
  NOTE_REQUIRED(422),
  COUNT_NOT_STARTED(422),
  COUNTED_QTY_REQUIRED(422),
  DUPLICATE_LINE(422),
  OPENING_ALREADY_SET(422),
  BELOW_RESERVED(422);

  private final int status;

  StockError(int status) {
    this.status = status;
  }

  public int status() {
    return status;
  }
}
