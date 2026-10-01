package com.thaishopfun.oms.checkout;

/** Invalid checkout reservation request (section 4.8 {@code BAD_REQUEST}). */
public final class CheckoutBadRequestException extends RuntimeException {

  CheckoutBadRequestException(String message) {
    super(message);
  }
}
