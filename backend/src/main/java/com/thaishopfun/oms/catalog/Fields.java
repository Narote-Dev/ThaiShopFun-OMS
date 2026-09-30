package com.thaishopfun.oms.catalog;

/**
 * Input rules shared by the REST API and the CSV import. Each check returns an error text, or null
 * when the value is fine, so the import can collect every error instead of stopping at the first.
 */
public final class Fields {

  public static final int MAX_NAME = 200;
  public static final int MAX_CODE = 64;
  public static final int MAX_BARCODE = 64;
  public static final int MAX_WAREHOUSE_CODE = 32;
  public static final int MAX_WEIGHT_G = 1_000_000;
  public static final int MAX_COMPONENT_QTY = 10_000;
  public static final int MAX_COMPONENTS = 50;

  private Fields() {}

  /** Trimmed text, or null when blank. */
  public static String trim(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.strip();
    return trimmed.isEmpty() ? null : trimmed;
  }

  public static String nameError(String value) {
    if (value == null) {
      return "is required";
    }
    if (value.length() > MAX_NAME) {
      return "must be at most " + MAX_NAME + " characters";
    }
    if (hasControl(value)) {
      return "must not contain control characters";
    }
    return null;
  }

  /**
   * SKU codes are referenced as {@code CODE:qty|CODE:qty} in the import, so {@code :} and {@code |}
   * are not allowed. No whitespace either.
   */
  public static String skuCodeError(String value) {
    if (value == null) {
      return "is required";
    }
    if (value.length() > MAX_CODE) {
      return "must be at most " + MAX_CODE + " characters";
    }
    if (hasWhitespaceOrControl(value) || value.indexOf(':') >= 0 || value.indexOf('|') >= 0) {
      return "must not contain spaces, ':' or '|'";
    }
    return null;
  }

  public static String barcodeError(String value) {
    if (value == null) {
      return null;
    }
    if (value.length() > MAX_BARCODE) {
      return "must be at most " + MAX_BARCODE + " characters";
    }
    if (hasWhitespaceOrControl(value)) {
      return "must not contain spaces";
    }
    return null;
  }

  public static String warehouseCodeError(String value) {
    if (value == null) {
      return "is required";
    }
    if (value.length() > MAX_WAREHOUSE_CODE) {
      return "must be at most " + MAX_WAREHOUSE_CODE + " characters";
    }
    if (hasWhitespaceOrControl(value)) {
      return "must not contain spaces";
    }
    return null;
  }

  public static String weightError(Integer value) {
    if (value == null) {
      return null;
    }
    if (value < 0 || value > MAX_WEIGHT_G) {
      return "must be between 0 and " + MAX_WEIGHT_G;
    }
    return null;
  }

  public static String qtyError(Integer value) {
    if (value == null) {
      return "is required";
    }
    if (value < 1 || value > MAX_COMPONENT_QTY) {
      return "must be between 1 and " + MAX_COMPONENT_QTY;
    }
    return null;
  }

  /** Throws a 422 for the first failing check. */
  public static void require(String field, String error) {
    if (error != null) {
      throw CatalogApiException.invalid(field + " " + error);
    }
  }

  /** LIKE pattern text with {@code \}, {@code %} and {@code _} escaped (ESCAPE '\'). */
  public static String likeEscape(String value) {
    StringBuilder out = new StringBuilder(value.length() + 8);
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c == '\\' || c == '%' || c == '_') {
        out.append('\\');
      }
      out.append(c);
    }
    return out.toString();
  }

  private static boolean hasControl(String value) {
    for (int i = 0; i < value.length(); i++) {
      if (Character.isISOControl(value.charAt(i))) {
        return true;
      }
    }
    return false;
  }

  private static boolean hasWhitespaceOrControl(String value) {
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (Character.isWhitespace(c) || Character.isISOControl(c) || Character.isSpaceChar(c)) {
        return true;
      }
    }
    return false;
  }
}
