package com.thaishopfun.mocktsf;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * {@code X-Signature: t=<unix>,v1=<hex>} over {@code t + "." + rawBody}. The same construction OMS
 * uses for the inbox and the outbox.
 */
public final class Hmacs {

  public static final long MAX_SKEW_SECONDS = 300;

  private Hmacs() {}

  public static String header(String secret, long epochSeconds, byte[] rawBody) {
    String timestamp = Long.toString(epochSeconds);
    return "t=" + timestamp + ",v1=" + sign(secret, timestamp, rawBody);
  }

  public static String sign(String secret, String timestamp, byte[] rawBody) {
    byte[] prefix = (timestamp + ".").getBytes(StandardCharsets.UTF_8);
    byte[] message = new byte[prefix.length + rawBody.length];
    System.arraycopy(prefix, 0, message, 0, prefix.length);
    System.arraycopy(rawBody, 0, message, prefix.length, rawBody.length);
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      return HexFormat.of().formatHex(mac.doFinal(message));
    } catch (Exception ex) {
      throw new IllegalStateException("HMAC-SHA256 is not available");
    }
  }

  public static boolean valid(List<String> secrets, String header, byte[] rawBody, long nowEpoch) {
    Parsed parsed = parse(header);
    if (parsed == null || rawBody == null || secrets == null || secrets.isEmpty()) {
      return false;
    }
    byte[] provided = decode(parsed.hex());
    if (provided == null) {
      return false;
    }
    boolean matches = false;
    for (String secret : secrets) {
      byte[] expected = decode(sign(secret, parsed.timestampText(), rawBody));
      if (expected != null) {
        matches |= MessageDigest.isEqual(expected, provided);
      }
    }
    long skew = Math.abs(nowEpoch - parsed.timestamp());
    return matches && skew <= MAX_SKEW_SECONDS;
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
