package com.thaishopfun.oms.staging;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.DynamicPropertyRegistry;

/** JWKS + property wiring for staging integration tests (matches {@code test-signing-key.pem}). */
public final class StagingTestSupport {

  static final String ISSUER = "http://127.0.0.1/staging-idp";
  private static final String PEM;
  private static final String JWKS_URI;

  static {
    try {
      PEM =
          Files.readString(
              new ClassPathResource("staging/test-signing-key.pem").getFile().toPath(),
              StandardCharsets.UTF_8);
      RSAPrivateKey privateKey = parsePrivateKey(PEM);
      KeyFactory keyFactory = KeyFactory.getInstance("RSA");
      RSAPrivateCrtKey crt = (RSAPrivateCrtKey) privateKey;
      RSAKey rsaKey =
          new RSAKey.Builder(
                  (java.security.interfaces.RSAPublicKey)
                      keyFactory.generatePublic(
                          new RSAPublicKeySpec(crt.getModulus(), crt.getPublicExponent())))
              .privateKey(privateKey)
              .keyID("staging-test-1")
              .build();
      byte[] body = new JWKSet(rsaKey.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
      HttpServer server = HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext(
          "/jwks",
          exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
          });
      server.setExecutor(command -> new Thread(command, "staging-test-jwks").start());
      server.start();
      JWKS_URI = "http://127.0.0.1:" + server.getAddress().getPort() + "/jwks";
    } catch (Exception ex) {
      throw new ExceptionInInitializerError(ex);
    }
  }

  private StagingTestSupport() {}

  public static void register(DynamicPropertyRegistry registry) throws IOException {
    String accounts =
        Files.readString(
            new ClassPathResource("staging/demo-accounts-integration.json").getFile().toPath(),
            StandardCharsets.UTF_8);
    registry.add("oms.security.issuer", () -> ISSUER);
    registry.add("oms.security.jwks-uri", () -> JWKS_URI);
    registry.add("oms.security.audience", () -> "oms");
    registry.add("oms.security.internal-audience", () -> "oms-internal");
    registry.add("oms.security.internal-client-ids", () -> "tsf");
    registry.add("oms.inbox.hmac-secrets", () -> "test-inbox-hmac");
    registry.add("oms.inbox.worker-enabled", () -> "false");
    // application-staging.yml requires env placeholders; keep test-only values from
    // application-test.yml.
    registry.add("oms.pii.keys", () -> "test1:dGVzdC1vbmx5LXBpaS1rZXktMS1ub3QtYS1zZWNyZXQ=");
    registry.add("oms.pii.active-key-id", () -> "test1");
    registry.add("oms.pii.hash-key", () -> "dGVzdC1vbmx5LXBpaS1obWFjLW5vdC1hLXNlY3JldCE=");
    registry.add("oms.outbox.publisher-enabled", () -> "false");
    registry.add("oms.outbox.destination-url", () -> "http://127.0.0.1:9/unused");
    registry.add("oms.outbox.webhook-secret", () -> "test-outbox-secret-0123456789abcdef");
    registry.add("oms.tsf.base-url", () -> "http://127.0.0.1:9");
    registry.add("oms.tsf.token-uri", () -> "http://127.0.0.1:9/token");
    registry.add("oms.tsf.client-secret", () -> "staging-test-tsf-secret");
    registry.add("oms.staging.jwt-signing-key-pem", () -> PEM);
    registry.add("oms.staging.jwt-key-id", () -> "staging-test-1");
    registry.add("oms.staging.cors-allowed-origins", () -> "http://localhost");
    registry.add("oms.staging.demo-accounts", () -> accounts);
    registry.add("oms.staging.demo-seed-on-startup", () -> "false");
    registry.add("oms.staging.login-lockout-threshold", () -> "3");
    registry.add("oms.staging.login-max-attempts", () -> "100");
  }

  private static RSAPrivateKey parsePrivateKey(String pem) throws Exception {
    String base64 =
        pem.replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replaceAll("\\s", "");
    byte[] der = Base64.getDecoder().decode(base64);
    return (RSAPrivateKey)
        KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
  }
}
