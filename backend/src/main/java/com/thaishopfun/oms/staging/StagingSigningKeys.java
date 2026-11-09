package com.thaishopfun.oms.staging;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import java.security.interfaces.RSAPublicKey;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Loads the staging JWT RSA key from the environment. Never written to disk. */
@Component
@Profile("staging")
class StagingSigningKeys {

  private final RSAKey key;

  StagingSigningKeys(StagingProperties properties) {
  // Step 1: Require an explicit signing key. Staging must not mint tokens with an ephemeral key.
    String pem = properties.getJwtSigningKeyPem();
    if (pem == null || pem.isBlank()) {
      throw new IllegalStateException(
          "OMS_STAGING_JWT_RSA_PRIVATE_KEY is required when the staging profile is active");
    }
    try {
      RSAPrivateKey privateKey = parsePrivateKey(pem);
      KeyFactory keyFactory = KeyFactory.getInstance("RSA");
      RSAPublicKey publicKey;
      if (privateKey instanceof RSAPrivateCrtKey crt) {
        publicKey =
            (RSAPublicKey)
                keyFactory.generatePublic(new RSAPublicKeySpec(crt.getModulus(), crt.getPublicExponent()));
      } else {
        throw new IllegalStateException("OMS_STAGING_JWT_RSA_PRIVATE_KEY must be an RSA CRT private key");
      }
      this.key =
          new RSAKey.Builder(publicKey)
              .privateKey(privateKey)
              .keyID(properties.getJwtKeyId())
              .build();
    } catch (Exception ex) {
      throw new IllegalStateException("OMS_STAGING_JWT_RSA_PRIVATE_KEY is invalid", ex);
    }
  }

  RSAKey key() {
    return key;
  }

  String jwksJson() {
    return new JWKSet(key.toPublicJWK()).toString();
  }

  private static RSAPrivateKey parsePrivateKey(String raw) throws Exception {
    String trimmed = raw.trim();
    if (trimmed.contains("BEGIN")) {
      String base64 =
          trimmed
              .replace("-----BEGIN PRIVATE KEY-----", "")
              .replace("-----END PRIVATE KEY-----", "")
              .replace("-----BEGIN RSA PRIVATE KEY-----", "")
              .replace("-----END RSA PRIVATE KEY-----", "")
              .replaceAll("\\s", "");
      byte[] der = Base64.getDecoder().decode(base64);
      KeyFactory factory = KeyFactory.getInstance("RSA");
      return (RSAPrivateKey) factory.generatePrivate(new PKCS8EncodedKeySpec(der));
    }
    byte[] der = Base64.getDecoder().decode(trimmed.getBytes(StandardCharsets.UTF_8));
    KeyFactory factory = KeyFactory.getInstance("RSA");
    return (RSAPrivateKey) factory.generatePrivate(new PKCS8EncodedKeySpec(der));
  }
}
