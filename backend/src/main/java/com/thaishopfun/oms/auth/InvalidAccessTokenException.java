package com.thaishopfun.oms.auth;

/** A user token parsed, but a required claim is missing or not in the allowed set. */
class InvalidAccessTokenException extends RuntimeException {

  InvalidAccessTokenException(String message) {
    super(message);
  }
}
