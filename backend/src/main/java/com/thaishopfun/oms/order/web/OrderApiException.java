package com.thaishopfun.oms.order.web;

/** OMS orders API rejection with a stable {@code error} code for section 4.8 bodies. */
public class OrderApiException extends RuntimeException {

  private final int status;
  private final String code;

  public OrderApiException(int status, String code, String message) {
    super(message);
    this.status = status;
    this.code = code;
  }

  public static OrderApiException notFound() {
    return new OrderApiException(404, "NOT_FOUND", "Order not found");
  }

  public static OrderApiException invalid(String message) {
    return new OrderApiException(422, "VALIDATION_FAILED", message);
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
}
