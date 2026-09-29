package com.thaishopfun.mocktsf;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import com.thaishopfun.mocktsf.idp.IdpController;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "spring.config.name=mock-tsf")
class MockTsfApiTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final List<String> OMS_BODIES = new CopyOnWriteArrayList<>();
  private static HttpServer oms;

  @BeforeAll
  static void fakeOms() throws Exception {
    oms = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    oms.createContext(
        "/",
        exchange -> {
          OMS_BODIES.add(
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          byte[] body =
              "{\"error\":\"NOT_FOUND\",\"message\":\"missing\",\"trace_id\":\"abc12345\"}"
                  .getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(404, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    oms.start();
  }

  @LocalServerPort private int port;

  @Autowired private OmsEndpoint endpoint;
  @Autowired private SigningKeys keys;

  private final HttpClient http = HttpClient.newBuilder().build();

  @BeforeEach
  void pointAtFakeOms() {
    OMS_BODIES.clear();
    endpoint.setBaseUrl("http://127.0.0.1:" + oms.getAddress().getPort());
  }

  @Test
  void rejectsPayloadThatDoesNotMatchSchema() throws Exception {
    HttpResponse<String> response =
        post(
            "/control/events/send",
            "{\"event\":{\"event_id\":\"evt-bad\",\"event_type\":\"order.paid\"}}");
    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(JSON.readTree(response.body()).path("error").asString())
        .isEqualTo("SCHEMA_VIOLATION");
    assertThat(OMS_BODIES).isEmpty();
  }

  @Test
  void receivesVerifiedOmsEventsAndRejectsBadOnes() throws Exception {
    String good = stockEvent("evt-stock-1");
    HttpResponse<String> unsigned = postRaw("/internal/v1/oms-events", good, null, "evt-stock-1");
    assertThat(unsigned.statusCode()).isEqualTo(401);

    String badShape = "{\"event_id\":\"evt-bad-shape\"}";
    HttpResponse<String> badSchema =
        postRaw(
            "/internal/v1/oms-events",
            badShape,
            Hmacs.header(
                "dev-outbox-webhook-secret-local-only",
                Instant.now().getEpochSecond(),
                bytes(badShape)),
            "evt-bad-shape");
    assertThat(badSchema.statusCode()).isEqualTo(400);
    assertThat(JSON.readTree(badSchema.body()).path("error").asString())
        .isEqualTo("SCHEMA_VIOLATION");

    String signature =
        Hmacs.header(
            "dev-outbox-webhook-secret-local-only", Instant.now().getEpochSecond(), bytes(good));
    HttpResponse<String> accepted =
        postRaw("/internal/v1/oms-events", good, signature, "evt-stock-1");
    assertThat(accepted.statusCode()).isEqualTo(202);
    HttpResponse<String> duplicate =
        postRaw("/internal/v1/oms-events", good, signature, "evt-stock-1");
    assertThat(duplicate.statusCode()).isEqualTo(200);

    HttpResponse<String> stored = get("/control/received-events");
    assertThat(stored.statusCode()).isEqualTo(200);
    assertThat(stored.body()).contains("evt-stock-1").doesNotContain("evt-bad-shape");
  }

  @Test
  void pkceIssuesSection41Claims() throws Exception {
    String verifier = "pkce-verifier-0123456789-abcdefghijklmnopqrstuvwxyz";
    String challenge = IdpController.s256(verifier);
    String authorize =
        "/tsf-idp/authorize?response_type=code&client_id=oms"
            + "&redirect_uri="
            + enc("http://127.0.0.1/callback")
            + "&code_challenge="
            + enc(challenge)
            + "&code_challenge_method=S256&state=xyz&login_hint=owner-grace";
    HttpResponse<String> redirect =
        http.send(request(authorize).GET().build(), HttpResponse.BodyHandlers.ofString());
    assertThat(redirect.statusCode()).isEqualTo(302);
    String code = query(redirect.headers().firstValue("location").orElseThrow(), "code");
    HttpResponse<String> token =
        http.send(
            request("/tsf-idp/token")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        "grant_type=authorization_code&client_id=oms&redirect_uri="
                            + enc("http://127.0.0.1/callback")
                            + "&code="
                            + enc(code)
                            + "&code_verifier="
                            + enc(verifier)))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(token.statusCode()).isEqualTo(200);
    JsonNode body = JSON.readTree(token.body());
    SignedJWT jwt = SignedJWT.parse(body.path("access_token").asString());
    assertThat(jwt.verify(new RSASSAVerifier(keys.publicKey()))).isTrue();
    assertThat(jwt.getJWTClaimsSet().getAudience()).containsExactly("oms");
    assertThat(jwt.getJWTClaimsSet().getStringClaim("tsf_shop_id")).isEqualTo("shop_grace");
    assertThat(jwt.getJWTClaimsSet().getStringClaim("shop_role")).isEqualTo("OWNER");
    assertThat(jwt.getJWTClaimsSet().getJSONObjectClaim("membership").get("status"))
        .isEqualTo("GRACE");
    assertThat(jwt.getJWTClaimsSet().getLongClaim("ent_ver")).isEqualTo(1L);
    assertThat(body.path("refresh_token").asString()).isNotBlank();
  }

  @Test
  void restRequiresClientCredentialsAndIdempotency() throws Exception {
    assertThat(get("/internal/v1/orders/TSF-240929-000123").statusCode()).isEqualTo(401);
    String service = serviceToken("oms-service", "dev-oms-service-secret");
    HttpResponse<String> order = get("/internal/v1/orders/TSF-240929-000123", service);
    assertThat(order.statusCode()).isEqualTo(200);
    assertThat(JSON.readTree(order.body()).path("order_id").asString())
        .isEqualTo("TSF-240929-000123");

    HttpResponse<String> missingKey =
        post(
            "/internal/v1/orders/TSF-240929-000123/shipments",
            "{\"carrier\":\"FLASH\"}",
            service,
            null);
    assertThat(missingKey.statusCode()).isEqualTo(400);

    HttpResponse<String> bad =
        post(
            "/internal/v1/orders/TSF-240929-000123/shipments",
            "{\"carrier\":1}",
            service,
            "ship-key");
    assertThat(bad.statusCode()).isEqualTo(400);
    assertThat(JSON.readTree(bad.body()).path("error").asString()).isEqualTo("SCHEMA_VIOLATION");

    HttpResponse<String> created =
        post(
            "/internal/v1/orders/TSF-240929-000123/shipments",
            "{\"carrier\":\"FLASH\"}",
            service,
            "ship-key");
    assertThat(created.statusCode()).isEqualTo(201);
    HttpResponse<String> replay =
        post(
            "/internal/v1/orders/TSF-240929-000123/shipments",
            "{\"carrier\":\"FLASH\"}",
            service,
            "ship-key");
    assertThat(replay.body()).isEqualTo(created.body());
    HttpResponse<String> conflict =
        post(
            "/internal/v1/orders/TSF-240929-000123/shipments",
            "{\"carrier\":\"KERRY\"}",
            service,
            "ship-key");
    assertThat(conflict.statusCode()).isEqualTo(409);

    String shipmentId = JSON.readTree(created.body()).path("shipment_id").asString();
    HttpResponse<byte[]> label =
        http.send(
            request("/internal/v1/shipments/" + shipmentId + "/label")
                .header("Authorization", "Bearer " + service)
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofByteArray());
    assertThat(label.statusCode()).isEqualTo(200);
    assertThat(new String(label.body(), StandardCharsets.US_ASCII)).startsWith("%PDF");
  }

  @Test
  void afterReservationExpiryStampsOccurredAt() throws Exception {
    String event =
        "{"
            + "\"reservation_expires_at\":\"2026-09-29T08:30:00Z\","
            + "\"event\":"
            + stockEvent("evt-after-expiry").replace("2026-09-29T08:15:02Z", "2026-09-29T08:00:00Z")
            + "}";
    HttpResponse<String> response = post("/control/events/after-reservation-expiry", event);
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(OMS_BODIES).hasSize(1);
    assertThat(JSON.readTree(OMS_BODIES.get(0)).path("occurred_at").asString())
        .isEqualTo("2026-09-29T08:30:01Z");
  }

  private String serviceToken(String clientId, String secret) throws Exception {
    HttpResponse<String> token =
        http.send(
            request("/tsf-idp/token")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        "grant_type=client_credentials&client_id="
                            + clientId
                            + "&client_secret="
                            + secret))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(token.statusCode()).isEqualTo(200);
    return JSON.readTree(token.body()).path("access_token").asString();
  }

  private HttpResponse<String> get(String path) throws Exception {
    return get(path, null);
  }

  private HttpResponse<String> get(String path, String bearer) throws Exception {
    HttpRequest.Builder builder = request(path).GET();
    if (bearer != null) {
      builder.header("Authorization", "Bearer " + bearer);
    }
    return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> post(String path, String body) throws Exception {
    return post(path, body, null, null);
  }

  private HttpResponse<String> post(String path, String body, String bearer, String idempotency)
      throws Exception {
    return postRaw(path, body, null, null, bearer, idempotency);
  }

  private HttpResponse<String> postRaw(String path, String body, String signature, String eventId)
      throws Exception {
    return postRaw(path, body, signature, eventId, null, null);
  }

  private HttpResponse<String> postRaw(
      String path, String body, String signature, String eventId, String bearer, String idempotency)
      throws Exception {
    HttpRequest.Builder builder =
        request(path)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body));
    if (signature != null) {
      builder.header("X-Signature", signature);
    }
    if (eventId != null) {
      builder.header("X-Event-Id", eventId);
    }
    if (bearer != null) {
      builder.header("Authorization", "Bearer " + bearer);
    }
    if (idempotency != null) {
      builder.header("Idempotency-Key", idempotency);
    }
    return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }

  private HttpRequest.Builder request(String path) {
    return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
  }

  private static byte[] bytes(String body) {
    return body.getBytes(StandardCharsets.UTF_8);
  }

  private static String stockEvent(String eventId) {
    return "{"
        + "\"event_id\":\""
        + eventId
        + "\","
        + "\"event_type\":\"stock.updated\","
        + "\"schema_version\":1,"
        + "\"occurred_at\":\"2026-09-29T08:15:02Z\","
        + "\"tsf_shop_id\":\"shop_active\","
        + "\"aggregate_id\":\"stock\","
        + "\"aggregate_version\":1,"
        + "\"data\":{\"items\":[{\"listing_sku_id\":\"tsf_sku_7781\",\"seller_sku\":\"TSHIRT-BLK-M\",\"available\":18,\"stock_version\":1042}]}"
        + "}";
  }

  private static String enc(String value) {
    return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private static String query(String location, String name) {
    String query = URI.create(location).getRawQuery();
    for (String part : query.split("&")) {
      int split = part.indexOf('=');
      if (split > 0 && name.equals(part.substring(0, split))) {
        return URLDecoder.decode(part.substring(split + 1), StandardCharsets.UTF_8);
      }
    }
    throw new IllegalStateException("missing " + name);
  }
}
