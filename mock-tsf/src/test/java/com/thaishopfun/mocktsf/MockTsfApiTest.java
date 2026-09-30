package com.thaishopfun.mocktsf;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import com.thaishopfun.mocktsf.contract.ContractValidator;
import com.thaishopfun.mocktsf.events.FaultSchedule;
import com.thaishopfun.mocktsf.idp.IdpController;
import com.thaishopfun.mocktsf.idp.TokenIssuer;
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
  @Autowired private FaultSchedule faults;

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
  void pickerListsShopsForOpenIdScope() throws Exception {
    String verifier = "pkce-verifier-0123456789-abcdefghijklmnopqrstuvwxyz";
    String authorize =
        "/tsf-idp/authorize?response_type=code&client_id=oms-web"
            + "&redirect_uri="
            + enc("http://127.0.0.1:5173/")
            + "&code_challenge="
            + enc(IdpController.s256(verifier))
            + "&code_challenge_method=S256&state=xyz&scope="
            + enc("openid oms");
    HttpResponse<String> page =
        http.send(request(authorize).GET().build(), HttpResponse.BodyHandlers.ofString());
    assertThat(page.statusCode()).isEqualTo(200);
    assertThat(page.body()).contains("Active Shop").contains("scope=openid%20oms");
  }

  @Test
  void pickerEncodesReservedCharactersInState() throws Exception {
    String verifier = "pkce-verifier-0123456789-abcdefghijklmnopqrstuvwxyz";
    String authorize =
        "/tsf-idp/authorize?response_type=code&client_id=oms-web"
            + "&redirect_uri="
            + enc("http://127.0.0.1:5173/")
            + "&code_challenge="
            + enc(IdpController.s256(verifier))
            + "&code_challenge_method=S256&state="
            + enc("a&b=c")
            + "&scope="
            + enc("openid oms");
    HttpResponse<String> page =
        http.send(request(authorize).GET().build(), HttpResponse.BodyHandlers.ofString());
    assertThat(page.statusCode()).isEqualTo(200);
    assertThat(page.body()).contains("state=a%26b%3Dc").doesNotContain("state=a&amp;b");
  }

  @Test
  void pkceIssuesSection41Claims() throws Exception {
    String verifier = "pkce-verifier-0123456789-abcdefghijklmnopqrstuvwxyz";
    String challenge = IdpController.s256(verifier);
    String authorize =
        "/tsf-idp/authorize?response_type=code&client_id=oms-web"
            + "&redirect_uri="
            + enc("http://127.0.0.1/callback")
            + "&code_challenge="
            + enc(challenge)
            + "&code_challenge_method=S256&state=xyz&nonce=n-1&login_hint=owner-grace";
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
                        "grant_type=authorization_code&client_id=oms-web&redirect_uri="
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
    assertThat(jwt.getHeader().getType().toString()).isEqualTo(TokenIssuer.ACCESS_TOKEN_TYPE);
    assertThat(jwt.getJWTClaimsSet().getAudience()).containsExactly("oms");
    assertThat(userClaimErrors(body.path("access_token").asString())).isEmpty();
    assertThat(jwt.getJWTClaimsSet().getStringClaim("tsf_shop_id")).isEqualTo("shop_grace");
    assertThat(jwt.getJWTClaimsSet().getStringClaim("shop_role")).isEqualTo("OWNER");
    assertThat(jwt.getJWTClaimsSet().getJSONObjectClaim("membership").get("status"))
        .isEqualTo("GRACE");
    assertThat(jwt.getJWTClaimsSet().getLongClaim("ent_ver")).isEqualTo(1L);
    assertThat(body.path("refresh_token").asString()).isNotBlank();
    SignedJWT idToken = SignedJWT.parse(body.path("id_token").asString());
    assertThat(idToken.verify(new RSASSAVerifier(keys.publicKey()))).isTrue();
    assertThat(idToken.getJWTClaimsSet().getIssuer()).isEqualTo("http://localhost:8090/tsf-idp");
    assertThat(idToken.getJWTClaimsSet().getSubject()).isEqualTo("owner-grace");
    assertThat(idToken.getHeader().getType().toString()).isEqualTo("JWT");
    assertThat(idToken.getJWTClaimsSet().getAudience()).containsExactly("oms-web");
    assertThat(idToken.getJWTClaimsSet().getStringClaim("nonce")).isEqualTo("n-1");
    assertThat(idToken.getJWTClaimsSet().getExpirationTime()).isNotNull();
    assertThat(idToken.getJWTClaimsSet().getIssueTime()).isNotNull();
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
            + orderCreated("evt-after-expiry", "2026-09-29T08:00:00Z")
            + "}";
    HttpResponse<String> response = post("/control/events/after-reservation-expiry", event);
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(OMS_BODIES).hasSize(1);
    assertThat(JSON.readTree(OMS_BODIES.get(0)).path("occurred_at").asString())
        .isEqualTo("2026-09-29T08:30:01Z");

    OMS_BODIES.clear();
    HttpResponse<String> wrongType =
        post(
            "/control/events/after-reservation-expiry",
            "{"
                + "\"reservation_expires_at\":\"2026-09-29T08:30:00Z\","
                + "\"event\":"
                + stockEvent("evt-not-reservation")
                + "}");
    assertThat(wrongType.statusCode()).isEqualTo(400);
    assertThat(OMS_BODIES).isEmpty();

    String future =
        java.time.Instant.now()
            .plusSeconds(3600)
            .truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
            .toString();
    HttpResponse<String> futureStamp =
        post(
            "/control/events/after-reservation-expiry",
            "{"
                + "\"reservation_expires_at\":\""
                + future
                + "\","
                + "\"event\":"
                + orderCreated("evt-future", "2026-09-29T08:00:00Z")
                + "}");
    assertThat(futureStamp.statusCode()).isEqualTo(400);
    assertThat(futureStamp.body()).contains("occurred_at would be in the future");
  }

  @Test
  void rejectsBadEventDataNulAndForeignTypes() throws Exception {
    String badData = stockEvent("evt-bad-data").replaceFirst("\"data\":\\{.*}$", "\"data\":{}}");
    HttpResponse<String> rejected = post("/control/events/send", "{\"event\":" + badData + "}");
    assertThat(rejected.statusCode()).isEqualTo(400);
    assertThat(JSON.readTree(rejected.body()).path("error").asString())
        .isEqualTo("SCHEMA_VIOLATION");

    String nul = stockEvent("evt-nul") + "\u0000";
    HttpResponse<String> nulResponse =
        postRaw(
            "/internal/v1/oms-events",
            nul,
            Hmacs.header(
                "dev-outbox-webhook-secret-local-only", Instant.now().getEpochSecond(), bytes(nul)),
            "evt-nul");
    assertThat(nulResponse.statusCode()).isEqualTo(400);
    assertThat(JSON.readTree(nulResponse.body()).path("error").asString()).isEqualTo("BAD_REQUEST");

    String escaped = stockEvent("evt-esc").replace("TSHIRT-BLK-M", "TSH" + "\\" + "u0000IRT");
    HttpResponse<String> escapedResponse =
        postRaw(
            "/internal/v1/oms-events",
            escaped,
            Hmacs.header(
                "dev-outbox-webhook-secret-local-only",
                Instant.now().getEpochSecond(),
                bytes(escaped)),
            "evt-esc");
    assertThat(escapedResponse.statusCode()).isEqualTo(400);
    assertThat(JSON.readTree(escapedResponse.body()).path("message").asString())
        .isEqualTo("Body contains an unsupported character");

    String foreign = orderCreated("evt-foreign", "2026-09-29T08:15:02Z");
    HttpResponse<String> foreignResponse =
        postRaw(
            "/internal/v1/oms-events",
            foreign,
            Hmacs.header(
                "dev-outbox-webhook-secret-local-only",
                Instant.now().getEpochSecond(),
                bytes(foreign)),
            "evt-foreign");
    assertThat(foreignResponse.statusCode()).isEqualTo(400);
    assertThat(foreignResponse.body()).contains("not accepted");
  }

  @Test
  void faultInjectionReturns429ThenTheRealResponse() throws Exception {
    HttpResponse<String> armed =
        post(
            "/control/faults",
            "{\"method\":\"GET\",\"path\":\"/internal/v1/orders/TSF-240929-000123\",\"status\":429,\"times\":1,\"retry_after\":30}");
    assertThat(armed.statusCode()).isEqualTo(200);
    HttpResponse<String> anonymous = get("/internal/v1/orders/TSF-240929-000123");
    assertThat(anonymous.statusCode()).isEqualTo(401);
    String service = serviceToken("oms-service", "dev-oms-service-secret");
    HttpResponse<String> limited = get("/internal/v1/orders/TSF-240929-000123", service);
    assertThat(limited.statusCode()).isEqualTo(429);
    assertThat(limited.headers().firstValue("Retry-After").orElse("")).isEqualTo("30");
    assertThat(JSON.readTree(limited.body()).path("error").asString()).isEqualTo("RATE_LIMITED");
    assertThat(get("/internal/v1/orders/TSF-240929-000123", service).statusCode()).isEqualTo(200);

    post(
        "/control/faults",
        "{\"method\":\"GET\",\"path\":\"/internal/v1/orders/TSF-240929-000123\",\"status\":503,\"times\":1}");
    HttpResponse<String> down = get("/internal/v1/orders/TSF-240929-000123", service);
    assertThat(down.statusCode()).isEqualTo(503);
    assertThat(down.headers().firstValue("Retry-After")).isEmpty();
    assertThat(faults.pending()).isZero();
  }

  @Test
  void tokenPreflightAllowsTheViteOriginOnly() throws Exception {
    HttpResponse<String> allowed =
        http.send(
            request("/tsf-idp/token")
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .header("Origin", "http://localhost:5173")
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "content-type")
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(allowed.statusCode()).isEqualTo(200);
    assertThat(allowed.headers().firstValue("Access-Control-Allow-Origin").orElse(""))
        .isEqualTo("http://localhost:5173");
    assertThat(allowed.headers().firstValue("Access-Control-Allow-Methods").orElse(""))
        .contains("POST");

    HttpResponse<String> blocked =
        http.send(
            request("/tsf-idp/token")
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .header("Origin", "https://evil.example")
                .header("Access-Control-Request-Method", "POST")
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(blocked.headers().firstValue("Access-Control-Allow-Origin")).isEmpty();
  }

  @Test
  void userTokenOverridesDoNotRequireAMembershipEvent() throws Exception {
    HttpResponse<String> response =
        post(
            "/control/user-token",
            "{\"login_hint\":\"owner-active\",\"ent_ver\":9,\"status\":\"SUSPENDED\",\"expires_at\":\"2020-01-01T00:00:00Z\"}");
    assertThat(response.statusCode()).isEqualTo(200);
    SignedJWT jwt = SignedJWT.parse(JSON.readTree(response.body()).path("access_token").asString());
    assertThat(jwt.getJWTClaimsSet().getLongClaim("ent_ver")).isEqualTo(9L);
    assertThat(jwt.getJWTClaimsSet().getJSONObjectClaim("membership").get("status"))
        .isEqualTo("SUSPENDED");
    assertThat(jwt.getJWTClaimsSet().getJSONObjectClaim("membership").get("expires_at"))
        .isEqualTo("2020-01-01T00:00:00Z");
  }

  @Test
  void sameIdempotencyKeyCreatesOneShipment() throws Exception {
    String service = serviceToken("oms-service", "dev-oms-service-secret");
    String key = "parallel-" + java.util.UUID.randomUUID();
    java.util.concurrent.ExecutorService pool =
        java.util.concurrent.Executors.newFixedThreadPool(8);
    try {
      java.util.List<java.util.concurrent.Future<HttpResponse<String>>> calls =
          new java.util.ArrayList<>();
      for (int i = 0; i < 8; i++) {
        calls.add(
            pool.submit(
                () ->
                    post(
                        "/internal/v1/orders/TSF-240929-000123/shipments",
                        "{\"carrier\":\"FLASH\"}",
                        service,
                        key)));
      }
      java.util.Set<String> bodies = new java.util.HashSet<>();
      for (java.util.concurrent.Future<HttpResponse<String>> call : calls) {
        HttpResponse<String> response = call.get();
        assertThat(response.statusCode()).isEqualTo(201);
        bodies.add(response.body());
      }
      assertThat(bodies).hasSize(1);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void receivedStoreKeepsTheLastThousand() {
    com.thaishopfun.mocktsf.events.ReceivedEventStore store =
        new com.thaishopfun.mocktsf.events.ReceivedEventStore();
    for (int i = 0; i < 1005; i++) {
      assertThat(
              store.add(
                  new com.thaishopfun.mocktsf.events.ReceivedEventStore.Received(
                      "e" + i, "stock.updated", "2026-09-29T08:15:02Z", "{}")))
          .isTrue();
    }
    assertThat(store.all()).hasSize(1000);
    assertThat(store.all().get(0).eventId()).isEqualTo("e5");
    assertThat(
            store.add(
                new com.thaishopfun.mocktsf.events.ReceivedEventStore.Received(
                    "e1004", "stock.updated", "2026-09-29T08:15:02Z", "{}")))
        .isFalse();
  }

  @Test
  void incompleteErrorBodyIsNotSchemaValid() throws Exception {
    com.sun.net.httpserver.HttpServer bad =
        com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
    bad.createContext(
        "/",
        exchange -> {
          byte[] body = "{\"error\":\"NOT_FOUND\"}".getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(404, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    bad.start();
    try {
      endpoint.setBaseUrl("http://127.0.0.1:" + bad.getAddress().getPort());
      HttpResponse<String> response =
          post(
              "/control/checkout/reservations",
              "{\"checkout_id\":\"chk_1\",\"tsf_shop_id\":\"shop_45021\",\"items\":[{\"listing_sku_id\":\"tsf_sku_7781\",\"qty\":1}]}");
      assertThat(response.statusCode()).isEqualTo(200);
      JsonNode result = JSON.readTree(response.body());
      assertThat(result.path("oms_status").asInt()).isEqualTo(404);
      assertThat(result.path("response_schema_valid").asBoolean()).isFalse();
    } finally {
      bad.stop(0);
      endpoint.setBaseUrl("http://127.0.0.1:" + oms.getAddress().getPort());
    }
  }

  @Test
  void emptyAndHtmlCheckoutBodiesStayInTheReport() throws Exception {
    java.util.concurrent.atomic.AtomicInteger calls =
        new java.util.concurrent.atomic.AtomicInteger();
    com.sun.net.httpserver.HttpServer bad =
        com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
    bad.createContext(
        "/",
        exchange -> {
          exchange.getRequestBody().readAllBytes();
          int n = calls.incrementAndGet();
          if (n == 1) {
            exchange.sendResponseHeaders(201, -1);
            exchange.close();
            return;
          }
          byte[] html = "<html>not json</html>".getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "text/html");
          exchange.sendResponseHeaders(n == 2 ? 409 : 500, html.length);
          exchange.getResponseBody().write(html);
          exchange.close();
        });
    bad.start();
    try {
      endpoint.setBaseUrl("http://127.0.0.1:" + bad.getAddress().getPort());
      String request =
          "{\"checkout_id\":\"chk_1\",\"tsf_shop_id\":\"shop_45021\",\"items\":[{\"listing_sku_id\":\"tsf_sku_7781\",\"qty\":1}]}";
      for (int expected : new int[] {201, 409, 500}) {
        HttpResponse<String> response = post("/control/checkout/reservations", request);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        JsonNode result = JSON.readTree(response.body());
        assertThat(result.path("oms_status").asInt()).isEqualTo(expected);
        assertThat(result.path("response_schema_valid").asBoolean()).isFalse();
        if (expected == 201) {
          assertThat(result.path("oms_body").asString()).isEmpty();
        } else {
          assertThat(result.path("oms_body").asString()).contains("<html>");
        }
      }
    } finally {
      bad.stop(0);
      endpoint.setBaseUrl("http://127.0.0.1:" + oms.getAddress().getPort());
    }
  }

  @Test
  void checkoutReportFollowsTheOperation() throws Exception {
    java.util.concurrent.atomic.AtomicInteger posts =
        new java.util.concurrent.atomic.AtomicInteger();
    java.util.concurrent.atomic.AtomicInteger deletes =
        new java.util.concurrent.atomic.AtomicInteger();
    String created =
        "{\"reservation_id\":\"rsv_1\",\"expires_at\":\"2026-09-29T08:30:00Z\",\"enforced\":true,"
            + "\"items\":[{\"listing_sku_id\":\"tsf_sku_7781\",\"qty\":1,\"enforced\":true}]}";
    com.sun.net.httpserver.HttpServer scripted =
        com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
    scripted.createContext(
        "/",
        exchange -> {
          exchange.getRequestBody().readAllBytes();
          String method = exchange.getRequestMethod();
          if ("POST".equals(method) && posts.incrementAndGet() == 1) {
            byte[] body = created.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(201, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
            return;
          }
          if ("POST".equals(method)) {
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
            return;
          }
          if ("DELETE".equals(method) && deletes.incrementAndGet() == 1) {
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
            return;
          }
          byte[] body = created.getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(201, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    scripted.start();
    try {
      endpoint.setBaseUrl("http://127.0.0.1:" + scripted.getAddress().getPort());
      String request =
          "{\"checkout_id\":\"chk_1\",\"tsf_shop_id\":\"shop_45021\",\"items\":[{\"listing_sku_id\":\"tsf_sku_7781\",\"qty\":1}]}";
      JsonNode reserved = JSON.readTree(post("/control/checkout/reservations", request).body());
      assertThat(reserved.path("oms_status").asInt()).isEqualTo(201);
      assertThat(reserved.path("response_schema_valid").asBoolean()).isTrue();
      JsonNode wrongRelease = JSON.readTree(post("/control/checkout/reservations", request).body());
      assertThat(wrongRelease.path("oms_status").asInt()).isEqualTo(204);
      assertThat(wrongRelease.path("response_schema_valid").asBoolean()).isFalse();
      JsonNode released = JSON.readTree(delete("/control/checkout/reservations/rsv_1").body());
      assertThat(released.path("oms_status").asInt()).isEqualTo(204);
      assertThat(released.path("response_schema_valid").asBoolean()).isTrue();
      JsonNode wrongReserve = JSON.readTree(delete("/control/checkout/reservations/rsv_1").body());
      assertThat(wrongReserve.path("oms_status").asInt()).isEqualTo(201);
      assertThat(wrongReserve.path("response_schema_valid").asBoolean()).isFalse();
    } finally {
      scripted.stop(0);
      endpoint.setBaseUrl("http://127.0.0.1:" + oms.getAddress().getPort());
    }
  }

  @Test
  void bumpShopIsInTheCatalog() throws Exception {
    String service = serviceToken("oms-service", "dev-oms-service-secret");
    HttpResponse<String> orders = get("/internal/v1/shops/shop_bump/orders", service);
    assertThat(orders.statusCode()).isEqualTo(200);
    assertThat(JSON.readTree(orders.body()).path("orders").isArray()).isTrue();
  }

  @Test
  void duplicateMembershipDoesNotRewriteTheSeed() throws Exception {
    java.util.concurrent.atomic.AtomicInteger calls =
        new java.util.concurrent.atomic.AtomicInteger();
    com.sun.net.httpserver.HttpServer scripted =
        com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
    scripted.createContext(
        "/",
        exchange -> {
          exchange.getRequestBody().readAllBytes();
          int status = calls.incrementAndGet() == 1 ? 202 : 200;
          byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(status, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    scripted.start();
    try {
      endpoint.setBaseUrl("http://127.0.0.1:" + scripted.getAddress().getPort());
      String first =
          "{\"event\":{\"event_id\":\"evt-seed-1\",\"event_type\":\"membership.changed\",\"schema_version\":1,\"occurred_at\":\"2026-09-29T08:15:02Z\",\"tsf_shop_id\":\"shop_bump\",\"aggregate_id\":\"shop_bump\",\"data\":{\"tier\":\"PRO\",\"status\":\"ACTIVE\",\"ent_ver\":5,\"expires_at\":\"2027-01-01T00:00:00Z\"}}}";
      assertThat(post("/control/events/send", first).statusCode()).isEqualTo(200);
      String later =
          "{\"event\":{\"event_id\":\"evt-seed-2\",\"event_type\":\"membership.changed\",\"schema_version\":1,\"occurred_at\":\"2026-09-29T08:15:03Z\",\"tsf_shop_id\":\"shop_bump\",\"aggregate_id\":\"shop_bump\",\"data\":{\"tier\":\"PRO\",\"status\":\"SUSPENDED\",\"ent_ver\":9,\"expires_at\":\"2027-01-01T00:00:00Z\"}}}";
      assertThat(post("/control/events/send", later).statusCode()).isEqualTo(200);
      HttpResponse<String> token = post("/control/user-token", "{\"login_hint\":\"owner-bump\"}");
      SignedJWT jwt = SignedJWT.parse(JSON.readTree(token.body()).path("access_token").asString());
      assertThat(jwt.getJWTClaimsSet().getLongClaim("ent_ver")).isEqualTo(5L);
      assertThat(jwt.getJWTClaimsSet().getJSONObjectClaim("membership").get("status"))
          .isEqualTo("ACTIVE");
    } finally {
      scripted.stop(0);
      endpoint.setBaseUrl("http://127.0.0.1:" + oms.getAddress().getPort());
    }
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

  private HttpResponse<String> delete(String path) throws Exception {
    return http.send(request(path).DELETE().build(), HttpResponse.BodyHandlers.ofString());
  }

  private HttpRequest.Builder request(String path) {
    return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
  }

  private static byte[] bytes(String body) {
    return body.getBytes(StandardCharsets.UTF_8);
  }

  private static List<String> userClaimErrors(String accessToken) throws Exception {
    SignedJWT jwt = SignedJWT.parse(accessToken);
    String payload = jwt.getPayload().toString();
    List<String> errors = ContractValidator.classpath().restErrors("user-claims", payload);
    String withoutEmail = payload.replaceFirst(",\"email\":\"[^\"]*\"", "");
    if (!withoutEmail.equals(payload)) {
      errors.addAll(ContractValidator.classpath().restErrors("user-claims", withoutEmail));
    }
    String withoutJti = payload.replaceFirst(",\"jti\":\"[^\"]*\"", "");
    if (ContractValidator.classpath().restErrors("user-claims", withoutJti).isEmpty()) {
      errors.add("jti must be required");
    }
    return errors;
  }

  private static String orderCreated(String eventId, String occurredAt) {
    return "{"
        + "\"event_id\":\""
        + eventId
        + "\","
        + "\"event_type\":\"order.created\","
        + "\"schema_version\":1,"
        + "\"occurred_at\":\""
        + occurredAt
        + "\","
        + "\"tsf_shop_id\":\"shop_active\","
        + "\"aggregate_id\":\"TSF-240929-000123\","
        + "\"aggregate_version\":1,"
        + "\"data\":{"
        + "\"order_id\":\"TSF-240929-000123\","
        + "\"reservation_id\":\"rsv_1\","
        + "\"payment_method\":\"COD\","
        + "\"payment_expires_at\":\"2026-09-29T08:45:00Z\","
        + "\"currency\":\"THB\","
        + "\"totals\":{\"subtotal\":1,\"shipping_fee\":0,\"discount\":0,\"grand_total\":1},"
        + "\"recipient\":{\"name\":\"A\",\"phone\":\"1\",\"address\":{\"line1\":\"1\",\"district\":\"d\",\"province\":\"p\",\"postcode\":\"10110\"}},"
        + "\"ship_by\":\"2026-10-01T00:00:00Z\","
        + "\"lines\":[{\"line_id\":\"L1\",\"listing_sku_id\":\"sku\",\"seller_sku\":\"S\",\"name\":\"n\",\"qty\":1,\"unit_price\":1}]"
        + "}}";
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
