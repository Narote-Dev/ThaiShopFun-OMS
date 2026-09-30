package com.thaishopfun.mocktsf;

/** A contract or request failure with an HTTP status and a stable {@code error} code. */
public class ApiException extends RuntimeException {

  private final int status;
  private final String code;

  public ApiException(int status, String code, String message) {
    super(message);
    this.status = status;
    this.code = code;
  }

  public int status() {
    return status;
  }

  public String code() {
    return code;
  }

  public static ApiException schema(String message) {
    return new ApiException(400, "SCHEMA_VIOLATION", message);
  }

  public static ApiException badRequest(String code, String message) {
    return new ApiException(400, code, message);
  }
}
