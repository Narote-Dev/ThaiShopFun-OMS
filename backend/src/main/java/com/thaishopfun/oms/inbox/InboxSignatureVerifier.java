package com.thaishopfun.oms.inbox;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/** {@code X-Signature: t=<unix>,v1=<hex>} over {@code t + "." + rawBody}, window 300 seconds. */
@Component
public class InboxSignatureVerifier {

  static final long MAX_SKEW_SECONDS = 300;

  private final InboxProperties properties;
  private final Clock clock;

  public InboxSignatureVerifier(InboxProperties properties, Clock clock) {
    this.properties = properties;
    this.clock = clock;
  }

  public boolean valid(String header, byte[] rawBody) {
    // Step 1: A header we cannot parse is a rejection. Do not explain which part failed.
    Parsed parsed = parse(header);
    if (parsed == null || rawBody == null) {
      return false;
    }
    byte[] provided = decode(parsed.hex());
    if (provided == null) {
      return false;
    }
    // Step 2: Compare every configured key. Rotation keeps the previous secret in the list.
    byte[] message = message(parsed.timestampText(), rawBody);
    boolean matches = false;
    for (String secret : properties.secrets()) {
      matches |= MessageDigest.isEqual(hmac(secret, message), provided);
    }
    long skew = Math.abs(clock.instant().getEpochSecond() - parsed.timestamp());
    return matches && skew <= MAX_SKEW_SECONDS;
  }

  private static byte[] message(String timestampText, byte[] rawBody) {
    byte[] prefix = (timestampText + ".").getBytes(StandardCharsets.UTF_8);
    byte[] message = new byte[prefix.length + rawBody.length];
    System.arraycopy(prefix, 0, message, 0, prefix.length);
    System.arraycopy(rawBody, 0, message, prefix.length, rawBody.length);
    return message;
  }

  private static byte[] hmac(String secret, byte[] message) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      return mac.doFinal(message);
    } catch (Exception ex) {
      return new byte[0];
    }
  }

  private static byte[] decode(String hex) {
    try {
      return HexFormat.of().parseHex(hex);
    } catch (IllegalArgumentException ex) {
      return null;
    }
  }

  private static Parsed parse(String header) {
    if (header == null || header.isBlank()) {
      return null;
    }
    String timestampText = null;
    String hex = null;
    for (String part : header.split(",")) {
      int split = part.indexOf('=');
      if (split <= 0) {
        return null;
      }
      String key = part.substring(0, split).trim();
      String value = part.substring(split + 1).trim();
      if ("t".equals(key)) {
        timestampText = value;
      } else if ("v1".equals(key)) {
        hex = value;
      } else {
        return null;
      }
    }
    if (timestampText == null || timestampText.isEmpty() || hex == null || hex.isEmpty()) {
      return null;
    }
    try {
      return new Parsed(timestampText, Long.parseLong(timestampText), hex);
    } catch (NumberFormatException ex) {
      return null;
    }
  }

  private record Parsed(String timestampText, long timestamp, String hex) {}
}
