package com.thaishopfun.oms.inbox;

import java.math.BigInteger;
import tools.jackson.databind.JsonNode;

/** Signed 64-bit integers from JSON. {@code asLong()} truncates values outside that range. */
final class JsonLongs {

  private JsonLongs() {}

  static boolean fitsNonNegativeLong(JsonNode value) {
    return exactNonNegative(value) != null;
  }

  static Long exactNonNegative(JsonNode value) {
    // Step 1: Reject floats and numbers that do not fit in PostgreSQL bigint.
    if (value == null || value.isNull() || !value.isIntegralNumber()) {
      return null;
    }
    BigInteger number = value.bigIntegerValue();
    if (number.signum() < 0 || number.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0) {
      return null;
    }
    return number.longValue();
  }
}
