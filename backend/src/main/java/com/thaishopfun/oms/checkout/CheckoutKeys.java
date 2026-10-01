package com.thaishopfun.oms.checkout;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

final class CheckoutKeys {

  static final int MAX_KEY_LENGTH = 255;

  private CheckoutKeys() {}

  static String requireKey(String key) {
    if (key == null || key.isBlank()) {
      throw new IllegalArgumentException("idempotency key is required");
    }
    if (key.length() > MAX_KEY_LENGTH) {
      throw new IllegalArgumentException("idempotency key is longer than " + MAX_KEY_LENGTH);
    }
    return key;
  }

  static String sha256(String canonical) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 is not available", ex);
    }
  }
}
