package com.thaishopfun.oms.pii;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

/**
 * Parsed and validated {@link PiiProperties}. Error messages name the setting and the key id, never
 * a key value. {@link #toString()} prints key ids only.
 */
public final class PiiKeyRing {

  static final int KEY_BYTES = 32;
  static final int MIN_HASH_KEY_BYTES = 32;
  private static final Pattern KEY_ID = Pattern.compile("[A-Za-z0-9_-]{1,32}");

  private final Map<String, SecretKey> keys;
  private final String activeKeyId;
  private final SecretKey hashKey;

  private PiiKeyRing(Map<String, SecretKey> keys, String activeKeyId, SecretKey hashKey) {
    this.keys = keys;
    this.activeKeyId = activeKeyId;
    this.hashKey = hashKey;
  }

  /**
   * @throws IllegalStateException when the ring, the active key id, or the hash key is missing or
   *     invalid
   */
  public static PiiKeyRing parse(PiiProperties properties) {
    // Step 1: Parse kid:base64 entries. Every key is exactly 32 bytes (AES-256), ids are unique.
    String raw = properties.getKeys() == null ? "" : properties.getKeys().trim();
    if (raw.isEmpty()) {
      throw new IllegalStateException("oms.pii.keys (OMS_PII_KEYS) is required");
    }
    Map<String, SecretKey> keys = new LinkedHashMap<>();
    for (String entry : raw.split(",")) {
      String trimmed = entry.trim();
      int colon = trimmed.indexOf(':');
      if (colon <= 0) {
        throw new IllegalStateException("oms.pii.keys entries must be kid:base64-key");
      }
      String kid = trimmed.substring(0, colon).trim();
      if (!KEY_ID.matcher(kid).matches()) {
        throw new IllegalStateException("oms.pii.keys key id must match [A-Za-z0-9_-]{1,32}");
      }
      byte[] key = decode(trimmed.substring(colon + 1).trim(), "oms.pii.keys key " + kid);
      if (key.length != KEY_BYTES) {
        throw new IllegalStateException("oms.pii.keys key " + kid + " must be 32 bytes (base64)");
      }
      if (keys.put(kid, new SecretKeySpec(key, "AES")) != null) {
        throw new IllegalStateException("oms.pii.keys key id " + kid + " is listed twice");
      }
    }

    // Step 2: The active key must be in the ring.
    String active = properties.getActiveKeyId() == null ? "" : properties.getActiveKeyId().trim();
    if (active.isEmpty()) {
      throw new IllegalStateException("oms.pii.active-key-id is required");
    }
    if (!keys.containsKey(active)) {
      throw new IllegalStateException(
          "oms.pii.active-key-id " + active + " is not in oms.pii.keys");
    }

    // Step 3: The HMAC key is separate from every encryption key.
    String rawHash = properties.getHashKey() == null ? "" : properties.getHashKey().trim();
    if (rawHash.isEmpty()) {
      throw new IllegalStateException("oms.pii.hash-key (OMS_PII_HASH_KEY) is required");
    }
    byte[] hash = decode(rawHash, "oms.pii.hash-key");
    if (hash.length < MIN_HASH_KEY_BYTES) {
      throw new IllegalStateException("oms.pii.hash-key must be at least 32 bytes (base64)");
    }
    for (SecretKey key : keys.values()) {
      if (MessageDigest.isEqual(key.getEncoded(), hash)) {
        throw new IllegalStateException("oms.pii.hash-key must differ from every oms.pii.keys key");
      }
    }
    return new PiiKeyRing(
        Collections.unmodifiableMap(keys), active, new SecretKeySpec(hash, "HmacSHA256"));
  }

  private static byte[] decode(String value, String name) {
    try {
      return Base64.getDecoder().decode(value);
    } catch (IllegalArgumentException ex) {
      throw new IllegalStateException(name + " is not valid base64");
    }
  }

  String activeKeyId() {
    return activeKeyId;
  }

  byte[] activeKeyIdBytes() {
    return activeKeyId.getBytes(StandardCharsets.US_ASCII);
  }

  SecretKey activeKey() {
    return keys.get(activeKeyId);
  }

  SecretKey key(String kid) {
    return keys.get(kid);
  }

  SecretKey hashKey() {
    return hashKey;
  }

  public Set<String> keyIds() {
    return keys.keySet();
  }

  @Override
  public String toString() {
    return "PiiKeyRing[active=" + activeKeyId + ", keyIds=" + keys.keySet() + "]";
  }
}
