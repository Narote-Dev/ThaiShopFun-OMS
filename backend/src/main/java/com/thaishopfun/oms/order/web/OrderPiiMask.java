package com.thaishopfun.oms.order.web;

/** Server-side masking for order recipient fields. Never logs plaintext. */
public final class OrderPiiMask {

  private OrderPiiMask() {}

  public static String maskedPhone(String phoneLast4) {
    if (phoneLast4 == null || phoneLast4.isBlank()) {
      return null;
    }
    return "***-***-" + phoneLast4;
  }

  public static String maskedName(String name) {
    if (name == null || name.isBlank()) {
      return null;
    }
    String trimmed = name.trim();
    if (trimmed.length() == 1) {
      return trimmed.charAt(0) + "***";
    }
    return trimmed.substring(0, 1) + "***";
  }
}
