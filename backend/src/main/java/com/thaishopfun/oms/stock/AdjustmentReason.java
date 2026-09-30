package com.thaishopfun.oms.stock;

/**
 * {@code stock_document_line.reason_code} for ADJUSTMENT lines. App-level only: V4 keeps the column
 * free text. {@code OTHER} needs the document note to say why.
 */
public enum AdjustmentReason {
  DAMAGED,
  LOST,
  FOUND,
  DATA_ENTRY,
  OTHER;

  public static AdjustmentReason parse(String value) {
    if (value == null) {
      return null;
    }
    for (AdjustmentReason reason : values()) {
      if (reason.name().equals(value)) {
        return reason;
      }
    }
    return null;
  }
}
