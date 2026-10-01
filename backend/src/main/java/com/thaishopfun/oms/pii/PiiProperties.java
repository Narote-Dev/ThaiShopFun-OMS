package com.thaishopfun.oms.pii;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Recipient PII keys. {@code keys} is {@code kid:base64-32-byte-key}, comma-separated. Encrypt uses
 * {@code active-key-id}; decrypt accepts any key in the ring. {@code hash-key} (base64, at least 32
 * bytes) is the separate HMAC key for {@code phone_hash}. There is no default: the app refuses to
 * boot without them. {@link #toString()} never prints key material.
 */
@ConfigurationProperties(prefix = "oms.pii")
public class PiiProperties {

  private String keys = "";
  private String activeKeyId = "";
  private String hashKey = "";

  public String getKeys() {
    return keys;
  }

  public void setKeys(String keys) {
    this.keys = keys;
  }

  public String getActiveKeyId() {
    return activeKeyId;
  }

  public void setActiveKeyId(String activeKeyId) {
    this.activeKeyId = activeKeyId;
  }

  public String getHashKey() {
    return hashKey;
  }

  public void setHashKey(String hashKey) {
    this.hashKey = hashKey;
  }

  @Override
  public String toString() {
    return "PiiProperties[activeKeyId=" + activeKeyId + ", keys=***, hashKey=***]";
  }
}
