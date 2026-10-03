package com.thaishopfun.oms.order.web;

import java.util.List;
import java.util.Map;

/** OMS orders API rejection with a stable {@code error} code for section 4.8 bodies. */
public class OrderApiException extends RuntimeException {

  private final int status;
  private final String code;
  private final List<?> details;

  public OrderApiException(int status, String code, String message) {
    this(status, code, message, List.of());
  }

  public OrderApiException(int status, String code, String message, List<?> details) {
    super(message);
    this.status = status;
    this.code = code;
    this.details = details == null ? List.of() : List.copyOf(details);
  }

  public static OrderApiException notFound() {
    return new OrderApiException(404, "NOT_FOUND", "Order not found");
  }

  public static OrderApiException invalid(String message) {
    return new OrderApiException(422, "VALIDATION_FAILED", message);
  }

  public static OrderApiException fieldValidationFailed(String field, String message) {
    return new OrderApiException(
        400, "VALIDATION_FAILED", message, List.of(Map.of("field", field, "message", message)));
  }

  public static OrderApiException conflict(String code, String message) {
    return new OrderApiException(409, code, message);
  }

  public int status() {
    return status;
  }

  public String code() {
    return code;
  }

  public List<?> details() {
    return details;
  }
}
