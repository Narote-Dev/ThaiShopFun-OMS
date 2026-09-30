package com.thaishopfun.oms.stock;

/** The key was already used with a different request. T12A maps this to 409. */
public class IdempotencyConflictException extends RuntimeException {

  private final String scope;

  public IdempotencyConflictException(String scope) {
    super("IDEMPOTENCY_CONFLICT: key was used with a different request (" + scope + ")");
    this.scope = scope;
  }

  public String scope() {
    return scope;
  }
}
