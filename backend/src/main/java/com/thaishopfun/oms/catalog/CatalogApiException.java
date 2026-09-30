package com.thaishopfun.oms.catalog;

import java.util.List;

/**
 * Catalog and warehouse rejection. The message is safe to show. {@code details} carries per-row
 * import errors and is empty otherwise.
 */
public class CatalogApiException extends RuntimeException {

  private final int status;
  private final String code;
  private final List<ImportRowError> details;

  public CatalogApiException(int status, String code, String message) {
    this(status, code, message, List.of());
  }

  public CatalogApiException(
      int status, String code, String message, List<ImportRowError> details) {
    super(message);
    this.status = status;
    this.code = code;
    this.details = List.copyOf(details);
  }

  public static CatalogApiException notFound(String what) {
    return new CatalogApiException(404, "NOT_FOUND", what + " not found");
  }

  public static CatalogApiException invalid(String message) {
    return new CatalogApiException(422, "VALIDATION_FAILED", message);
  }

  public static CatalogApiException conflict(String code, String message) {
    return new CatalogApiException(409, code, message);
  }

  public int status() {
    return status;
  }

  public String code() {
    return code;
  }

  public List<ImportRowError> details() {
    return details;
  }
}
