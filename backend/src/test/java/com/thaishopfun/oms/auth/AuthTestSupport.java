package com.thaishopfun.oms.auth;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Date;
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
    registry.add("oms.security.internal-client-ids", () -> "tsf-checkout");
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
    return token(
        userId,
        shopId,
        status,
        membershipExpiry,
        entVer,
        audience,
        expiresAt,
        entitlements,
        "OWNER");
  }

  static String token(
      String userId,
      String shopId,
      String status,
      Instant membershipExpiry,
      long entVer,
      String audience,
      Instant expiresAt,
      List<String> entitlements,
      String role) {
    Map<String, Object> membership = new LinkedHashMap<>();
    membership.put("tier", "PRO");
    membership.put("status", status);
    if (membershipExpiry != null) {
      membership.put("expires_at", membershipExpiry.toString());
    }
    JwtClaimsSet.Builder claims =
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
            .claim("shop_role", role)
            .claim("membership", membership)
            .claim("entitlements", entitlements)
            .claim("ent_ver", entVer);
    if ("oms-internal".equals(audience)) {
      claims.claim("azp", userId);
    }
    JwtClaimsSet built = claims.build();
    JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).keyId(RSA_KEY.getKeyID()).build();
    return ENCODER.encode(JwtEncoderParameters.from(header, built)).getTokenValue();
  }

  static String invalidRoleToken(String userId, String shopId) {
    return token(
        userId,
        shopId,
        "ACTIVE",
        Instant.now().plusSeconds(86400),
        1,
        "oms",
        Instant.now().plusSeconds(600),
        List.of("oms"),
        "NOPE");
  }

  static String dualAudienceToken(String userId, String shopId) {
    JwtClaimsSet claims =
        JwtClaimsSet.builder()
            .issuer(ISSUER)
            .audience(List.of("oms", "oms-internal"))
            .subject(userId)
            .issuedAt(Instant.now().minusSeconds(30))
            .expiresAt(Instant.now().plusSeconds(600))
            .claim("tsf_shop_id", shopId)
            .claim("shop_role", "OWNER")
            .claim("membership", Map.of("tier", "PRO", "status", "ACTIVE"))
            .claim("entitlements", List.of("oms"))
            .claim("ent_ver", 1)
            .claim("azp", "tsf-checkout")
            .build();
    JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).keyId(RSA_KEY.getKeyID()).build();
    return ENCODER.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
  }

  static String wrongIssuerToken(String userId, String shopId) throws Exception {
    return sign(
        attackClaims("https://evil.example", shopId, userId),
        JWSAlgorithm.RS256,
        RSA_KEY.getKeyID(),
        true);
  }

  static String noneAlgorithmToken(String userId, String shopId) throws Exception {
    return new PlainJWT(attackClaims(ISSUER, shopId, userId)).serialize();
  }

  static String hs256WithPublicKeyToken(String userId, String shopId) throws Exception {
    SignedJWT jwt =
        new SignedJWT(
            new com.nimbusds.jose.JWSHeader.Builder(JWSAlgorithm.HS256)
                .keyID(RSA_KEY.getKeyID())
                .build(),
            attackClaims(ISSUER, shopId, userId));
    jwt.sign(new MACSigner(RSA_KEY.toRSAPublicKey().getEncoded()));
    return jwt.serialize();
  }

  static String unknownKeyIdToken(String userId, String shopId) throws Exception {
    return sign(attackClaims(ISSUER, shopId, userId), JWSAlgorithm.RS256, "missing-key", true);
  }

  private static JWTClaimsSet attackClaims(String issuer, String shopId, String userId) {
    return new JWTClaimsSet.Builder()
        .issuer(issuer)
        .audience("oms")
        .subject(userId)
        .expirationTime(Date.from(Instant.now().plusSeconds(600)))
        .issueTime(Date.from(Instant.now().minusSeconds(30)))
        .claim("tsf_shop_id", shopId)
        .claim("shop_role", "OWNER")
        .claim("ent_ver", 1)
        .build();
  }

  private static String sign(JWTClaimsSet claims, JWSAlgorithm algorithm, String keyId, boolean rsa)
      throws Exception {
    SignedJWT jwt =
        new SignedJWT(
            new com.nimbusds.jose.JWSHeader.Builder(algorithm).keyID(keyId).build(), claims);
    if (rsa) {
      jwt.sign(new RSASSASigner(RSA_KEY.toRSAPrivateKey()));
    }
    return jwt.serialize();
  }
}
