package com.thaishopfun.oms.order;

import java.time.Instant;
import java.util.UUID;

/**
 * A decrypted {@code order_recipient} row. After redaction {@code name}, {@code phone}, {@code
 * address}, and {@code phoneLast4} are null; only {@code province} and {@code postcode} remain.
 * {@link #toString()} masks the PII fields.
 */
public record StoredRecipient(
    UUID orderId,
    UUID tenantId,
    String name,
    String phone,
    String address,
    String phoneLast4,
    String province,
    String postcode,
    String piiStatus,
    Instant redactAfter) {

  public boolean redacted() {
    return "REDACTED".equals(piiStatus);
  }

  @Override
  public String toString() {
    return "StoredRecipient[orderId="
        + orderId
        + ", name=[PII], phone=[PII], address=[PII], phoneLast4="
        + phoneLast4
        + ", province="
        + province
        + ", postcode="
        + postcode
        + ", piiStatus="
        + piiStatus
        + "]";
  }
}
