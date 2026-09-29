package com.thaishopfun.oms.auth;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Shared Postgres 16 and a localhost JWKS. The app login is {@code oms_app}. */
final class AuthTestSupport {

  static final String APP_PASSWORD = "oms-app-test-only";
  static final String ISSUER = "http://127.0.0.1/tsf-test";

  static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("postgres:16-alpine").withInitScript("db/test-oms-app-login.sql");

  private static final RSAKey RSA_KEY;
  private static final JwtEncoder ENCODER;
  private static final String JWKS_URI;

  static {
    POSTGRES.start();
    try {
      RSA_KEY = new RSAKeyGenerator(2048).keyID("oms-test-key").generate();
      ENCODER = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(RSA_KEY)));
      byte[] body = new JWKSet(RSA_KEY.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
      HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext(
          "/jwks",
          exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
          });
      server.setExecutor(
          command -> {
            Thread thread = new Thread(command, "test-jwks");
            thread.setDaemon(true);
            thread.start();
          });
      server.start();
      JWKS_URI = "http://127.0.0.1:" + server.getAddress().getPort() + "/jwks";
    } catch (Exception ex) {
      throw new ExceptionInInitializerError(ex);
    }
  }

  private AuthTestSupport() {}

  static void register(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", () -> "oms_app");
    registry.add("spring.datasource.password", () -> APP_PASSWORD);
    registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
    registry.add("spring.flyway.user", POSTGRES::getUsername);
    registry.add("spring.flyway.password", POSTGRES::getPassword);
    registry.add("oms.security.issuer", () -> ISSUER);
    registry.add("oms.security.jwks-uri", () -> JWKS_URI);
    registry.add("oms.security.audience", () -> "oms");
    registry.add("oms.security.internal-audience", () -> "oms-internal");
  }

  static Connection admin() throws SQLException {
    return DriverManager.getConnection(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
  }

  static String userToken(
      String userId, String shopId, String status, Instant membershipExpiry, long entVer) {
    return token(
        userId,
        shopId,
        status,
        membershipExpiry,
        entVer,
        "oms",
        Instant.now().plusSeconds(600),
        List.of("oms"));
  }

  static String token(
      String userId,
      String shopId,
      String status,
      Instant membershipExpiry,
      long entVer,
      String audience,
      Instant expiresAt,
      List<String> entitlements) {
    Map<String, Object> membership = new LinkedHashMap<>();
    membership.put("tier", "PRO");
    membership.put("status", status);
    if (membershipExpiry != null) {
      membership.put("expires_at", membershipExpiry.toString());
    }
    JwtClaimsSet claims =
        JwtClaimsSet.builder()
            .issuer(ISSUER)
            .audience(List.of(audience))
            .subject(userId)
            .issuedAt(expiresAt.minusSeconds(600))
            .expiresAt(expiresAt)
            .claim("email", "owner-pii@shop.example")
            .claim("name", "Owner")
            .claim("shop_name", "Shop " + shopId)
            .claim("tsf_shop_id", shopId)
            .claim("shop_role", "OWNER")
            .claim("membership", membership)
            .claim("entitlements", entitlements)
            .claim("ent_ver", entVer)
            .build();
    JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).keyId(RSA_KEY.getKeyID()).build();
    return ENCODER.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
  }
}
