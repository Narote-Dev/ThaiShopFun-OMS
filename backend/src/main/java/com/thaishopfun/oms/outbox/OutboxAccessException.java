package com.thaishopfun.oms.outbox;

/** Admin retry rejection. The message is safe to show; it never includes the payload. */
public class OutboxAccessException extends RuntimeException {

  private final int status;
  private final String code;

  public OutboxAccessException(int status, String code, String message) {
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
}
