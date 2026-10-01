package com.thaishopfun.oms.pii;

/** A ciphertext that is malformed, uses an unknown key, or fails the GCM tag (tamper or AAD). */
public class PiiDecryptionException extends RuntimeException {

  public PiiDecryptionException(String message) {
    super(message);
  }

  public PiiDecryptionException(String message, Throwable cause) {
    super(message, cause);
  }
}
