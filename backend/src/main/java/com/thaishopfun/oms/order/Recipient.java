package com.thaishopfun.oms.order;

/**
 * Recipient PII in clear text, only in memory. {@code phone} is optional. {@code address} is the
 * full address as the channel sent it (the caller decides the format, for example JSON). {@link
 * #toString()} masks every PII field so a log line or an assertion message cannot leak it.
 */
public record Recipient(
    String name, String phone, String address, String province, String postcode) {

  @Override
  public String toString() {
    return "Recipient[name=[PII], phone=[PII], address=[PII], province="
        + province
        + ", postcode="
        + postcode
        + "]";
  }
}
