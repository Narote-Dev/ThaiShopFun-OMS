package com.thaishopfun.oms.stock;

/**
 * {@code stock_document.type}, with the ledger reason a posted line writes. ADJUSTMENT picks {@code
 * ADJUST_IN} or {@code ADJUST_OUT} by the sign of the line.
 */
public enum StockDocumentType {
  OPENING,
  RECEIVE,
  ADJUSTMENT,
  COUNT,
  WRITE_OFF;

  /** OPENING, ADJUSTMENT, COUNT, and WRITE_OFF change stock without a supplier trail. */
  public boolean requiresManager() {
    return this != RECEIVE;
  }

  public static StockDocumentType parse(String value) {
    if (value == null) {
      return null;
    }
    for (StockDocumentType type : values()) {
      if (type.name().equals(value)) {
        return type;
      }
    }
    return null;
  }
}
