package com.thaishopfun.mocktsf;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import java.security.interfaces.RSAPublicKey;
import org.springframework.stereotype.Component;

/** RSA key pair created at startup. Nothing is written to disk. */
@Component
public class SigningKeys {

  private final RSAKey key;

  public SigningKeys() {
    try {
      this.key = new RSAKeyGenerator(2048).keyID("mock-tsf-dev").generate();
    } catch (Exception ex) {
      throw new IllegalStateException("RSA key generation failed", ex);
    }
  }

  public RSAKey key() {
    return key;
  }

  public RSAPublicKey publicKey() {
    try {
      return key.toRSAPublicKey();
    } catch (Exception ex) {
      throw new IllegalStateException("RSA public key is unavailable", ex);
    }
  }

  public String jwksJson() {
    return new JWKSet(key.toPublicJWK()).toString();
  }
}
