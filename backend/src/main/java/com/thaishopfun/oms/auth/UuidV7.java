package com.thaishopfun.oms.auth;

import java.security.SecureRandom;
import java.util.UUID;

/** UUIDv7 for ids the application inserts. Provisioning functions use {@code gen_random_uuid}. */
// Change: public so the outbox appender can mint the same ids as the rest of the app.
public final class UuidV7 {

  private static final SecureRandom RANDOM = new SecureRandom();

  private UuidV7() {}

  public static UUID generate() {
    long millis = System.currentTimeMillis();
    byte[] bytes = new byte[16];
    bytes[0] = (byte) (millis >>> 40);
    bytes[1] = (byte) (millis >>> 32);
    bytes[2] = (byte) (millis >>> 24);
    bytes[3] = (byte) (millis >>> 16);
    bytes[4] = (byte) (millis >>> 8);
    bytes[5] = (byte) millis;
    byte[] random = new byte[10];
    RANDOM.nextBytes(random);
    System.arraycopy(random, 0, bytes, 6, 10);
    bytes[6] = (byte) ((bytes[6] & 0x0f) | 0x70);
    bytes[8] = (byte) ((bytes[8] & 0x3f) | 0x80);
    return uuid(bytes);
  }

  private static UUID uuid(byte[] bytes) {
    long msb = 0;
    long lsb = 0;
    for (int i = 0; i < 8; i++) {
      msb = (msb << 8) | (bytes[i] & 0xff);
    }
    for (int i = 8; i < 16; i++) {
      lsb = (lsb << 8) | (bytes[i] & 0xff);
    }
    return new UUID(msb, lsb);
  }
}
