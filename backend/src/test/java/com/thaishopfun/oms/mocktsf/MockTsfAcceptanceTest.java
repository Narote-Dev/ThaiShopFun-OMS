package com.thaishopfun.oms.mocktsf;

import static org.assertj.core.api.Assertions.assertThat;

import com.thaishopfun.mocktsf.MockTsfApplication;
import com.thaishopfun.mocktsf.OmsEndpoint;
import com.thaishopfun.oms.auth.AuthTestSupport;
import com.thaishopfun.oms.inbox.InboxWorker;
import com.thaishopfun.oms.outbox.OutboxAppender;
import com.thaishopfun.oms.outbox.OutboxDraft;
import com.thaishopfun.oms.outbox.OutboxPublisher;
import com.thaishopfun.oms.tenant.TenantContext;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * OMS against the in-process mock IdP, inbox sender, and outbox receiver. The mock jar is installed
 * before this module's tests.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MockTsfAcceptanceTest {

  private static final String ISSUER = "http://mock-tsf.test/tsf-idp";
  private static final String INBOX_SECRET = "dev-inbox-hmac-secret";
  private static final String OUTBOX_SECRET = "dev-outbox-webhook-secret-local-only";
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final HttpClient HTTP =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

  private static ConfigurableApplicationContext mock;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    startMock();
    AuthTestSupport.registerDatabase(registry);
    int mockPort = mockPort();
    registry.add("oms.security.issuer", () -> ISSUER);
    registry.add(
        "oms.security.jwks-uri",
        () -> "http://127.0.0.1:" + mockPort + "/tsf-idp/.well-known/jwks.json");
    registry.add("oms.security.audience", () -> "oms");
    registry.add("oms.security.internal-audience", () -> "oms-internal");
    registry.add("oms.security.internal-client-ids", () -> "tsf");
    registry.add("oms.inbox.hmac-secrets", () -> INBOX_SECRET);
    registry.add("oms.inbox.worker-enabled", () -> "false");
    registry.add("oms.inbox.jitter-ratio", () -> "0");
    registry.add("oms.outbox.publisher-enabled", () -> "false");
    registry.add("oms.outbox.jitter-ratio", () -> "0");
    registry.add(
        "oms.outbox.destination-url",
        () -> "http://127.0.0.1:" + mockPort + "/internal/v1/oms-events");
    registry.add("oms.outbox.webhook-secret", () -> OUTBOX_SECRET);
  }

  @AfterAll
  static void stopMock() {
    if (mock != null) {
      mock.close();
    }
  }

  @LocalServerPort private int port;

  @Autowired private InboxWorker worker;
  @Autowired private OutboxAppender appender;
  @Autowired private OutboxPublisher publisher;
  @Autowired private PlatformTransactionManager transactions;

  @BeforeEach
  void pointMockAtThisOms() throws Exception {
    mock.getBean(OmsEndpoint.class).setBaseUrl("http://127.0.0.1:" + port);
    try (Connection admin = AuthTestSupport.admin();
        var statement = admin.createStatement()) {
      statement.execute("TRUNCATE TABLE inbox_event, outbox_event");
    }
  }

  @AfterEach
  void clearTenant() {
    TenantContext.clear();
  }

  @Test
  void mockIdpTokensDriveMe() throws Exception {
    JsonNode active = me("owner-active");
    assertThat(active.path("entitlement").path("status").asString()).isEqualTo("ACTIVE");
    assertThat(active.path("tenant").path("tsf_shop_id").asString()).isEqualTo("shop_active");
    assertThat(active.path("tenant").path("name").asString()).isEqualTo("Active Shop");

    JsonNode grace = me("owner-grace");
    assertThat(grace.path("entitlement").path("status").asString()).isEqualTo("GRACE");
    HttpResult graceWrite = call("POST", "/api/v1/me", token("owner-grace"), null);
    assertThat(graceWrite.status()).isEqualTo(403);
    assertThat(JSON.readTree(graceWrite.body()).path("error").asString())
        .isEqualTo("ENTITLEMENT_GRACE");

    HttpResult expired = call("GET", "/api/v1/me", token("owner-expired"), null);
    assertThat(expired.status()).isEqualTo(403);
    JsonNode expiredBody = JSON.readTree(expired.body());
    assertThat(expiredBody.path("error").asString()).isEqualTo("ENTITLEMENT_INACTIVE");
    assertThat(expiredBody.path("message").asString()).isEqualTo("Membership expired");

    HttpResult suspended = call("GET", "/api/v1/me", token("owner-suspended"), null);
    assertThat(suspended.status()).isEqualTo(403);
    assertThat(JSON.readTree(suspended.body()).path("error").asString())
        .isEqualTo("ENTITLEMENT_INACTIVE");
  }

  @Test
  void repeatedMembershipIsASingleEffect() throws Exception {
    String shopId = "shop-repeat-" + UUID.randomUUID();
    ObjectNode event = membership(UUID.randomUUID().toString(), shopId, 4);
    ObjectNode body = JSON.createObjectNode();
    body.put("times", 5);
    body.set("event", event);

    JsonNode report = control("/control/events/repeat", body);
    assertThat(report.path("sent")).hasSize(5);
    assertThat(report.path("sent").get(0).path("http_status").asInt()).isEqualTo(202);
    for (int i = 1; i < 5; i++) {
      assertThat(report.path("sent").get(i).path("http_status").asInt()).isEqualTo(200);
    }

    assertThat(worker.processAvailable(20)).isEqualTo(1);
    String eventId = event.path("event_id").asString();
    assertThat(count("SELECT count(*) FROM inbox_event WHERE event_id = ?", eventId)).isEqualTo(1);
    assertThat(text("SELECT status FROM inbox_event WHERE event_id = ?", eventId))
        .isEqualTo("PROCESSED");
    assertThat(count("SELECT ent_ver FROM tenant WHERE tsf_shop_id = ?", shopId)).isEqualTo(4);
    assertThat(
            count(
                """
                SELECT count(*) FROM audit_log a
                JOIN tenant t ON t.id = a.tenant_id
                WHERE t.tsf_shop_id = ? AND a.action = 'membership.changed'
                """,
                shopId))
        .isEqualTo(1);
  }

  @Test
  void shuffledMembershipKeepsTheHighestEntVer() throws Exception {
    String shopId = "shop-shuffle-" + UUID.randomUUID();
    List<String> input = new ArrayList<>();
    ObjectNode body = JSON.createObjectNode();
    var events = body.putArray("events");
    for (int entVer = 1; entVer <= 3; entVer++) {
      ObjectNode event = membership(UUID.randomUUID().toString(), shopId, entVer);
      events.add(event);
      input.add(event.path("event_id").asString());
    }

    JsonNode report = control("/control/events/shuffle", body);
    assertThat(report.path("sent")).hasSize(3);
    List<String> sent = new ArrayList<>();
    for (JsonNode row : report.path("sent")) {
      assertThat(row.path("http_status").asInt()).isEqualTo(202);
      sent.add(row.path("event_id").asString());
    }
    assertThat(sent).containsExactlyInAnyOrderElementsOf(input);
    assertThat(sent).isNotEqualTo(input);

    assertThat(worker.processAvailable(20)).isEqualTo(3);
    assertThat(count("SELECT ent_ver FROM tenant WHERE tsf_shop_id = ?", shopId)).isEqualTo(3);
  }

  @Test
  void badSignatureAndStaleTimestampAre401() throws Exception {
    ObjectNode badEvent =
        membership(UUID.randomUUID().toString(), "shop-bad-" + UUID.randomUUID(), 1);
    ObjectNode badBody = JSON.createObjectNode();
    badBody.set("event", badEvent);
    JsonNode bad = control("/control/events/bad-signature", badBody);
    assertThat(bad.path("sent").get(0).path("http_status").asInt()).isEqualTo(401);
    assertThat(
            count(
                "SELECT count(*) FROM inbox_event WHERE event_id = ?",
                badEvent.path("event_id").asString()))
        .isZero();

    ObjectNode staleEvent =
        membership(UUID.randomUUID().toString(), "shop-stale-" + UUID.randomUUID(), 1);
    ObjectNode staleBody = JSON.createObjectNode();
    staleBody.put("skew_seconds", 301);
    staleBody.set("event", staleEvent);
    JsonNode stale = control("/control/events/stale", staleBody);
    assertThat(stale.path("sent").get(0).path("http_status").asInt()).isEqualTo(401);
    assertThat(
            count(
                "SELECT count(*) FROM inbox_event WHERE event_id = ?",
                staleEvent.path("event_id").asString()))
        .isZero();
  }

  @Test
  void outboxPublishIsReceivedAndVerified() throws Exception {
    JsonNode me = me("owner-active");
    UUID tenantId = UUID.fromString(me.path("tenant").path("id").asString());
    Map<String, Object> item = new LinkedHashMap<>();
    item.put("listing_sku_id", "tsf_sku_7781");
    item.put("seller_sku", "TSHIRT-BLK-M");
    item.put("available", 18);
    item.put("stock_version", 1042);
    UUID[] eventId = new UUID[1];
    TenantContext.set(tenantId, null);
    try {
      new TransactionTemplate(transactions)
          .executeWithoutResult(
              status ->
                  eventId[0] =
                      appender.append(
                          OutboxDraft.of(
                              "listing",
                              "tsf_sku_7781",
                              "stock.updated",
                              Map.of("items", List.of(item)),
                              1042)));
    } finally {
      TenantContext.clear();
    }

    assertThat(publisher.publishOnce()).isEqualTo(1);
    assertThat(text("SELECT status FROM outbox_event WHERE id = ?::uuid", eventId[0].toString()))
        .isEqualTo("SENT");

    JsonNode received =
        JSON.readTree(call("GET", mockUri("/control/received-events"), null, null).body());
    boolean found = false;
    for (JsonNode row : received.path("events")) {
      found |= eventId[0].toString().equals(row.path("event_id").asString());
    }
    assertThat(found).isTrue();
  }

  private static void startMock() {
    if (mock != null) {
      return;
    }
    SpringApplication app = MockTsfApplication.application();
    mock =
        app.run(
            "--server.port=0",
            "--server.address=127.0.0.1",
            "--mock.issuer=" + ISSUER,
            "--mock.oms-base-url=http://127.0.0.1:9",
            "--spring.main.banner-mode=off",
            "--spring.main.register-shutdown-hook=false");
  }

  private static int mockPort() {
    String port = mock.getEnvironment().getProperty("local.server.port");
    if (port == null || port.isBlank() || "0".equals(port)) {
      throw new IllegalStateException("mock-tsf did not bind a port");
    }
    return Integer.parseInt(port);
  }

  private JsonNode me(String loginHint) throws Exception {
    HttpResult result = call("GET", "/api/v1/me", token(loginHint), null);
    assertThat(result.status()).isEqualTo(200);
    return JSON.readTree(result.body());
  }

  private String token(String loginHint) throws Exception {
    ObjectNode body = JSON.createObjectNode();
    body.put("login_hint", loginHint);
    HttpResult result = call("POST", mockUri("/control/user-token"), null, body.toString());
    assertThat(result.status()).isEqualTo(200);
    return JSON.readTree(result.body()).path("access_token").asString();
  }

  private JsonNode control(String path, ObjectNode body) throws Exception {
    HttpResult result = call("POST", mockUri(path), null, body.toString());
    assertThat(result.status()).as(result.body()).isEqualTo(200);
    return JSON.readTree(result.body());
  }

  private static URI mockUri(String path) {
    return URI.create("http://127.0.0.1:" + mockPort() + path);
  }

  private HttpResult call(String method, String path, String bearer, String json) throws Exception {
    return call(method, URI.create("http://127.0.0.1:" + port + path), bearer, json);
  }

  private static HttpResult call(String method, URI uri, String bearer, String json)
      throws Exception {
    HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10));
    if (bearer != null) {
      builder.header("Authorization", "Bearer " + bearer);
    }
    if (json != null) {
      builder.header("Content-Type", "application/json");
      builder.method(method, HttpRequest.BodyPublishers.ofString(json));
    } else if ("POST".equals(method)) {
      builder.POST(HttpRequest.BodyPublishers.noBody());
    } else {
      builder.method(method, HttpRequest.BodyPublishers.noBody());
    }
    HttpResponse<String> response =
        HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    return new HttpResult(response.statusCode(), response.body() == null ? "" : response.body());
  }

  private static ObjectNode membership(String eventId, String shopId, int entVer) {
    ObjectNode data = JSON.createObjectNode();
    data.put("name", "Shop " + shopId);
    data.put("tier", "PRO");
    data.put("status", "ACTIVE");
    data.put("ent_ver", entVer);
    data.put("expires_at", "2027-01-01T00:00:00Z");
    ObjectNode event = JSON.createObjectNode();
    event.put("event_id", eventId);
    event.put("event_type", "membership.changed");
    event.put("schema_version", 1);
    event.put("occurred_at", Instant.now().truncatedTo(ChronoUnit.SECONDS).toString());
    event.put("tsf_shop_id", shopId);
    event.put("aggregate_id", shopId);
    event.put("aggregate_version", entVer);
    event.set("data", data);
    return event;
  }

  private static long count(String sql, String arg) throws Exception {
    String value = text(sql, arg);
    return value == null ? 0 : Long.parseLong(value);
  }

  private static String text(String sql, String arg) throws Exception {
    try (Connection admin = AuthTestSupport.admin();
        PreparedStatement statement = admin.prepareStatement(sql)) {
      statement.setString(1, arg);
      try (ResultSet rows = statement.executeQuery()) {
        if (!rows.next()) {
          return null;
        }
        return rows.getString(1);
      }
    }
  }

  private record HttpResult(int status, String body) {}
}
