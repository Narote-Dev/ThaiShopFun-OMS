package com.thaishopfun.oms.pii;

/** Encrypted {@code order_recipient} columns. The column name is part of the AAD. */
public enum PiiColumn {
  NAME("name_enc"),
  PHONE("phone_enc"),
  ADDRESS("address_enc");

  private final String columnName;

  PiiColumn(String columnName) {
    this.columnName = columnName;
  }

  public String columnName() {
    return columnName;
  }
}
