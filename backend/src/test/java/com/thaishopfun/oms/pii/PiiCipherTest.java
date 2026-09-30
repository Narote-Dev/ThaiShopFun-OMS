package com.thaishopfun.oms.pii;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PiiCipherTest {

  // Test-only keys: 32 bytes each, never used outside tests.
  static final String KEY_1 = b64("test-only-pii-key-1-not-a-secret");
  static final String KEY_2 = b64("test-only-pii-key-2-not-a-secret");
  static final String HASH_KEY = b64("test-only-pii-hmac-not-a-secret!");

  private static final UUID TENANT = UUID.randomUUID();
  private static final UUID ORDER = UUID.randomUUID();
  private static final String NAME = "สมชาย ใจดี";

  @Test
  void roundTripsUtf8AndKeepsNull() {
    PiiCipher cipher = cipher("k1:" + KEY_1, "k1");
    byte[] sealed = cipher.encrypt(NAME, TENANT, ORDER, PiiColumn.NAME);
    assertThat(cipher.decrypt(sealed, TENANT, ORDER, PiiColumn.NAME)).isEqualTo(NAME);
    assertThat(cipher.encrypt(null, TENANT, ORDER, PiiColumn.PHONE)).isNull();
    assertThat(cipher.decrypt(null, TENANT, ORDER, PiiColumn.PHONE)).isNull();
  }

  @Test
  void layoutIsVersionKeyIdNonceCiphertextTag() {
    PiiCipher cipher = cipher("k1:" + KEY_1, "k1");
    byte[] sealed = cipher.encrypt(NAME, TENANT, ORDER, PiiColumn.NAME);
    int plaintext = NAME.getBytes(StandardCharsets.UTF_8).length;
    // Step 1: 0x01 || len("k1") || "k1" || 12-byte nonce || ciphertext (same length) || 16-byte
    // tag.
    assertThat(sealed[0]).isEqualTo((byte) 1);
    assertThat(sealed[1]).isEqualTo((byte) 2);
    assertThat(new String(sealed, 2, 2, StandardCharsets.US_ASCII)).isEqualTo("k1");
    assertThat(sealed).hasSize(1 + 1 + 2 + 12 + plaintext + 16);
    // Step 2: The plaintext bytes do not appear in the output.
    assertThat(HexFormat.of().formatHex(sealed))
        .doesNotContain(HexFormat.of().formatHex(NAME.getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  void sameValueEncryptsDifferentlyEachTime() {
    PiiCipher cipher = cipher("k1:" + KEY_1, "k1");
    byte[] first = cipher.encrypt(NAME, TENANT, ORDER, PiiColumn.NAME);
    byte[] second = cipher.encrypt(NAME, TENANT, ORDER, PiiColumn.NAME);
    assertThat(first).isNotEqualTo(second);
    assertThat(cipher.decrypt(second, TENANT, ORDER, PiiColumn.NAME)).isEqualTo(NAME);
  }

  @Test
  void ciphertextIsBoundToTenantOrderAndColumn() {
    PiiCipher cipher = cipher("k1:" + KEY_1, "k1");
    byte[] sealed = cipher.encrypt(NAME, TENANT, ORDER, PiiColumn.NAME);
    // Step 1: Copied to another order, another tenant, or another column: the tag fails.
    assertThatThrownBy(() -> cipher.decrypt(sealed, TENANT, UUID.randomUUID(), PiiColumn.NAME))
        .isInstanceOf(PiiDecryptionException.class)
        .hasMessageContaining("authentication");
    assertThatThrownBy(() -> cipher.decrypt(sealed, UUID.randomUUID(), ORDER, PiiColumn.NAME))
        .isInstanceOf(PiiDecryptionException.class);
    assertThatThrownBy(() -> cipher.decrypt(sealed, TENANT, ORDER, PiiColumn.ADDRESS))
        .isInstanceOf(PiiDecryptionException.class);
    // Step 2: Swapping tenant and order ids is not the same AAD either.
    assertThatThrownBy(() -> cipher.decrypt(sealed, ORDER, TENANT, PiiColumn.NAME))
        .isInstanceOf(PiiDecryptionException.class);
  }

  @Test
  void anyTamperedByteFails() {
    PiiCipher cipher = cipher("k1:" + KEY_1, "k1");
    byte[] sealed = cipher.encrypt(NAME, TENANT, ORDER, PiiColumn.NAME);
    // Step 1: Flip each byte in turn: version, key id, nonce, ciphertext, and tag.
    for (int i = 0; i < sealed.length; i++) {
      byte[] tampered = sealed.clone();
      tampered[i] ^= 0x01;
      assertThatThrownBy(() -> cipher.decrypt(tampered, TENANT, ORDER, PiiColumn.NAME))
          .as("byte %d", i)
          .isInstanceOf(PiiDecryptionException.class)
          .satisfies(ex -> assertThat(ex.getMessage()).doesNotContain(NAME));
    }
    // Step 2: Truncated values are rejected before decryption.
    for (int length : List.of(0, 1, 2, 16, sealed.length - 1)) {
      byte[] truncated = java.util.Arrays.copyOf(sealed, length);
      assertThatThrownBy(() -> cipher.decrypt(truncated, TENANT, ORDER, PiiColumn.NAME))
          .as("length %d", length)
          .isInstanceOf(PiiDecryptionException.class);
    }
  }

  @Test
  void rotationDecryptsOldKeyAndEncryptsWithTheNewOne() {
    // Step 1: Written under k1.
    PiiCipher before = cipher("k1:" + KEY_1, "k1");
    byte[] old = before.encrypt(NAME, TENANT, ORDER, PiiColumn.NAME);

    // Step 2: The ring now has k2 active and k1 kept for reads.
    PiiCipher after = cipher("k2:" + KEY_2 + ",k1:" + KEY_1, "k2");
    assertThat(after.decrypt(old, TENANT, ORDER, PiiColumn.NAME)).isEqualTo(NAME);
    byte[] fresh = after.encrypt(NAME, TENANT, ORDER, PiiColumn.NAME);
    assertThat(new String(fresh, 2, fresh[1], StandardCharsets.US_ASCII)).isEqualTo("k2");
    assertThat(after.activeKeyId()).isEqualTo("k2");

    // Step 3: Once k1 is dropped from the ring, its values no longer decrypt.
    PiiCipher dropped = cipher("k2:" + KEY_2, "k2");
    assertThatThrownBy(() -> dropped.decrypt(old, TENANT, ORDER, PiiColumn.NAME))
        .isInstanceOf(PiiDecryptionException.class)
        .hasMessageContaining("k1");
    assertThat(dropped.decrypt(fresh, TENANT, ORDER, PiiColumn.NAME)).isEqualTo(NAME);
  }

  @Test
  void phoneHashIsStableAcrossFormats() {
    PiiCipher cipher = cipher("k1:" + KEY_1, "k1");
    byte[] expected = cipher.phoneHash("0812345678");
    // Step 1: National, dashed, spaced, and international forms hash the same.
    for (String phone :
        List.of("081-234-5678", "+66812345678", "66812345678", "081 234 5678", "(081) 2345678")) {
      assertThat(cipher.phoneHash(phone)).as(phone).isEqualTo(expected);
      assertThat(PiiCipher.phoneLast4(phone)).as(phone).isEqualTo("5678");
    }
    assertThat(PiiCipher.normalizePhone("081-234-5678")).isEqualTo("66812345678");
    assertThat(expected).hasSize(32);
    // Step 2: Another number, or another hash key, gives another hash.
    assertThat(cipher.phoneHash("0812345679")).isNotEqualTo(expected);
    PiiProperties other = properties("k1:" + KEY_1, "k1");
    other.setHashKey(KEY_2);
    assertThat(new PiiCipher(PiiKeyRing.parse(other)).phoneHash("0812345678"))
        .isNotEqualTo(expected);
    // Step 3: Garbage is rejected without echoing it.
    assertThatThrownBy(() -> cipher.phoneHash("call-me-12"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageNotContaining("call-me");
  }

  @Test
  void invalidConfigurationFailsWithoutPrintingKeys() {
    // Step 1: Missing ring, missing active id, active id not in ring, missing hash key.
    assertInvalid(properties("", "k1"), "oms.pii.keys");
    assertInvalid(properties("k1:" + KEY_1, ""), "oms.pii.active-key-id");
    assertInvalid(properties("k1:" + KEY_1, "k9"), "not in oms.pii.keys");
    PiiProperties noHash = properties("k1:" + KEY_1, "k1");
    noHash.setHashKey("");
    assertInvalid(noHash, "oms.pii.hash-key");

    // Step 2: Malformed entries: no colon, bad id, bad base64, wrong length, duplicate id.
    assertInvalid(properties(KEY_1, "k1"), "kid:base64-key");
    assertInvalid(properties("bad id:" + KEY_1, "k1"), "key id");
    assertInvalid(properties("k1:not*base64", "k1"), "not valid base64");
    assertInvalid(properties("k1:" + b64("short-key"), "k1"), "32 bytes");
    assertInvalid(properties("k1:" + KEY_1 + ",k1:" + KEY_2, "k1"), "twice");

    // Step 3: A short hash key, or a hash key equal to an encryption key.
    PiiProperties shortHash = properties("k1:" + KEY_1, "k1");
    shortHash.setHashKey(b64("short"));
    assertInvalid(shortHash, "at least 32 bytes");
    PiiProperties reused = properties("k1:" + KEY_1, "k1");
    reused.setHashKey(KEY_1);
    assertInvalid(reused, "must differ");
  }

  @Test
  void toStringNeverPrintsKeys() {
    PiiProperties properties = properties("k1:" + KEY_1, "k1");
    PiiCipher cipher = new PiiCipher(PiiKeyRing.parse(properties));
    for (String text : List.of(properties.toString(), cipher.toString())) {
      assertThat(text).doesNotContain(KEY_1).doesNotContain(HASH_KEY).contains("k1");
    }
  }

  private static void assertInvalid(PiiProperties properties, String message) {
    assertThatThrownBy(() -> PiiKeyRing.parse(properties))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(message)
        .satisfies(
            ex -> {
              assertThat(ex.getMessage()).doesNotContain(KEY_1).doesNotContain(KEY_2);
              assertThat(ex.getMessage()).doesNotContain(HASH_KEY);
            });
  }

  static PiiCipher cipher(String keys, String active) {
    return new PiiCipher(PiiKeyRing.parse(properties(keys, active)));
  }

  static PiiProperties properties(String keys, String active) {
    PiiProperties properties = new PiiProperties();
    properties.setKeys(keys);
    properties.setActiveKeyId(active);
    properties.setHashKey(HASH_KEY);
    return properties;
  }

  private static String b64(String value) {
    return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.US_ASCII));
  }
}
