package com.thaishopfun.oms.pii;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Application-level AES-256-GCM for {@code order_recipient}. Keys never pass through SQL.
 *
 * <p>Ciphertext layout ({@code bytea}): {@code version (1 byte, 0x01) || key id length (1 byte) ||
 * key id (ASCII) || nonce (12 random bytes) || ciphertext || GCM tag (16 bytes)}. The AAD is {@code
 * tenant_id (16 bytes) || order_id (16 bytes) || column name (UTF-8)}, so a value copied to another
 * row, tenant, or column fails the tag.
 *
 * <p>{@code phone_hash} is HMAC-SHA256 with a separate key over the normalized phone (digits only,
 * a Thai leading {@code 0} becomes {@code 66}). Nothing here logs, and no exception message carries
 * plaintext or key material.
 */
public final class PiiCipher {

  static final byte VERSION = 1;
  static final int NONCE_BYTES = 12;
  static final int TAG_BITS = 128;
  private static final int MIN_PHONE_DIGITS = 8;
  private static final int MAX_PHONE_DIGITS = 15;

  private final PiiKeyRing ring;
  private final SecureRandom random = new SecureRandom();

  public PiiCipher(PiiKeyRing ring) {
    this.ring = ring;
  }

  public byte[] encrypt(String plaintext, UUID tenantId, UUID orderId, PiiColumn column) {
    // Step 1: Validate. Null stays null so optional columns (phone) round-trip.
    if (plaintext == null) {
      return null;
    }
    byte[] kid = ring.activeKeyIdBytes();
    byte[] nonce = new byte[NONCE_BYTES];
    random.nextBytes(nonce);

    // Step 2: Encrypt with the active key, bound to (tenant, order, column).
    byte[] sealed;
    try {
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, ring.activeKey(), new GCMParameterSpec(TAG_BITS, nonce));
      cipher.updateAAD(aad(tenantId, orderId, column));
      sealed = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
    } catch (GeneralSecurityException ex) {
      throw new IllegalStateException("PII encryption failed", ex);
    }

    // Step 3: Prefix the header so decrypt can pick the key during rotation.
    return ByteBuffer.allocate(2 + kid.length + NONCE_BYTES + sealed.length)
        .put(VERSION)
        .put((byte) kid.length)
        .put(kid)
        .put(nonce)
        .put(sealed)
        .array();
  }

  public String decrypt(byte[] ciphertext, UUID tenantId, UUID orderId, PiiColumn column) {
    if (ciphertext == null) {
      return null;
    }
    // Step 1: Parse the header. Reject unknown versions and truncated values.
    ByteBuffer buffer = ByteBuffer.wrap(ciphertext);
    if (buffer.remaining() < 2 || buffer.get() != VERSION) {
      throw new PiiDecryptionException("PII ciphertext has an unknown format version");
    }
    int kidLength = buffer.get() & 0xff;
    if (kidLength == 0 || buffer.remaining() < kidLength + NONCE_BYTES + TAG_BITS / 8) {
      throw new PiiDecryptionException("PII ciphertext is truncated");
    }
    byte[] kidBytes = new byte[kidLength];
    buffer.get(kidBytes);
    String kid = new String(kidBytes, StandardCharsets.US_ASCII);
    SecretKey key = ring.key(kid);
    if (key == null) {
      throw new PiiDecryptionException("PII ciphertext uses key id " + kid + " not in the ring");
    }
    byte[] nonce = new byte[NONCE_BYTES];
    buffer.get(nonce);
    byte[] sealed = new byte[buffer.remaining()];
    buffer.get(sealed);

    // Step 2: Decrypt. A wrong row, tenant, column, or any flipped byte fails the tag.
    try {
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
      cipher.updateAAD(aad(tenantId, orderId, column));
      return new String(cipher.doFinal(sealed), StandardCharsets.UTF_8);
    } catch (GeneralSecurityException ex) {
      throw new PiiDecryptionException("PII ciphertext failed authentication", ex);
    }
  }

  /** HMAC-SHA256 (32 bytes) of {@link #normalizePhone}. */
  public byte[] phoneHash(String phone) {
    String normalized = normalizePhone(phone);
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(ring.hashKey());
      return mac.doFinal(normalized.getBytes(StandardCharsets.US_ASCII));
    } catch (GeneralSecurityException ex) {
      throw new IllegalStateException("PII phone hash failed", ex);
    }
  }

  public static String phoneLast4(String phone) {
    String normalized = normalizePhone(phone);
    return normalized.substring(normalized.length() - 4);
  }

  /**
   * Digits only. A Thai national number ({@code 0} prefix) becomes international {@code 66}, so
   * {@code 081-234-5678} and {@code +66812345678} both give {@code 66812345678}.
   *
   * @throws IllegalArgumentException without echoing the input
   */
  public static String normalizePhone(String phone) {
    // Step 1: Keep digits. Separators, spaces, and the + sign are formatting.
    if (phone == null) {
      throw new IllegalArgumentException("phone is required");
    }
    StringBuilder digits = new StringBuilder(phone.length());
    for (int i = 0; i < phone.length(); i++) {
      char c = phone.charAt(i);
      if (c >= '0' && c <= '9') {
        digits.append(c);
      }
    }
    // Step 2: Thai trunk prefix 0 -> country code 66.
    String normalized =
        digits.length() > 0 && digits.charAt(0) == '0'
            ? "66" + digits.substring(1)
            : digits.toString();
    if (normalized.length() < MIN_PHONE_DIGITS || normalized.length() > MAX_PHONE_DIGITS) {
      throw new IllegalArgumentException("phone must have 8 to 15 digits after normalization");
    }
    return normalized;
  }

  private static byte[] aad(UUID tenantId, UUID orderId, PiiColumn column) {
    if (tenantId == null || orderId == null || column == null) {
      throw new IllegalArgumentException("tenantId, orderId, and column are required for PII AAD");
    }
    byte[] name = column.columnName().getBytes(StandardCharsets.UTF_8);
    return ByteBuffer.allocate(32 + name.length)
        .putLong(tenantId.getMostSignificantBits())
        .putLong(tenantId.getLeastSignificantBits())
        .putLong(orderId.getMostSignificantBits())
        .putLong(orderId.getLeastSignificantBits())
        .put(name)
        .array();
  }

  public String activeKeyId() {
    return ring.activeKeyId();
  }

  @Override
  public String toString() {
    return "PiiCipher[" + ring + "]";
  }
}
