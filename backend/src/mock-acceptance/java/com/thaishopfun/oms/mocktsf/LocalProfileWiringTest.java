package com.thaishopfun.oms.mocktsf;

import static org.assertj.core.api.Assertions.assertThat;

import com.thaishopfun.mocktsf.MockTsfApplication;
import com.thaishopfun.mocktsf.OmsEndpoint;
import com.thaishopfun.oms.auth.AuthTestSupport;
import com.thaishopfun.oms.auth.OmsSecurityProperties;
import com.thaishopfun.oms.inbox.InboxProperties;
import com.thaishopfun.oms.outbox.OutboxAppender;
import com.thaishopfun.oms.outbox.OutboxDraft;
import com.thaishopfun.oms.outbox.OutboxProperties;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
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
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The {@code local} profile supplies issuer, client ids, and HMAC secrets. This test overrides only
 * the ports and URLs that depend on the random mock port.
 */
@ActiveProfiles("local")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LocalProfileWiringTest {

  private static final String ISSUER = "http://localhost:8090/tsf-idp";
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final HttpClient HTTP =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

  private static ConfigurableApplicationContext mock;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    startMock();
    AuthTestSupport.registerDatabase(registry);
    int mockPort = mockPort();
    registry.add(
        "oms.security.jwks-uri",
        () -> "http://127.0.0.1:" + mockPort + "/tsf-idp/.well-known/jwks.json");
    registry.add(
        "oms.outbox.destination-url",
        () -> "http://127.0.0.1:" + mockPort + "/internal/v1/oms-events");
  }

  @AfterAll
  static void stopMock() {
    if (mock != null) {
      mock.close();
    }
  }

  @LocalServerPort private int port;

  @Autowired private OmsSecurityProperties security;
  @Autowired private InboxProperties inbox;
  @Autowired private OutboxProperties outbox;
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

  @Test
  void localProfileTalksToTheMock() throws Exception {
    // Step 1: Issuer, client id, and secrets come from application-local.yml. Only the URLs move.
    assertThat(security.getIssuer()).isEqualTo(ISSUER);
    assertThat(security.getInternalClientIds()).containsExactly("tsf");
    assertThat(security.getJwksUri()).isEqualTo(mockBase() + "/tsf-idp/.well-known/jwks.json");
    assertThat(inbox.getHmacSecrets()).isEqualTo("dev-inbox-hmac-secret");
    assertThat(outbox.getWebhookSecret()).isEqualTo("dev-outbox-webhook-secret-local-only");
    assertThat(outbox.getDestinationUrl()).isEqualTo(mockBase() + "/internal/v1/oms-events");

    HttpResponse<String> token =
        HTTP.send(
            HttpRequest.newBuilder(URI.create(mockBase() + "/control/user-token"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"login_hint\":\"owner-active\"}"))
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    assertThat(token.statusCode()).isEqualTo(200);
    String access = JSON.readTree(token.body()).path("access_token").asString();
    HttpResponse<String> me =
        HTTP.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/me"))
                .header("Authorization", "Bearer " + access)
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    assertThat(me.statusCode()).as(me.body()).isEqualTo(200);

    ObjectNode event = membership(UUID.randomUUID().toString(), "shop-local-" + UUID.randomUUID());
    ObjectNode send = JSON.createObjectNode();
    send.set("event", event);
    HttpResponse<String> report =
        HTTP.send(
            HttpRequest.newBuilder(URI.create(mockBase() + "/control/events/send"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(send.toString()))
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    assertThat(report.statusCode()).as(report.body()).isEqualTo(200);
    assertThat(JSON.readTree(report.body()).path("sent").get(0).path("http_status").asInt())
        .isEqualTo(202);

    UUID tenantId = UUID.fromString(JSON.readTree(me.body()).path("tenant").path("id").asString());
    UUID eventId = appendStock(tenantId);
    String status = null;
    for (int attempt = 0; attempt < 10 && !"SENT".equals(status); attempt++) {
      publisher.publishOnce();
      status = text("SELECT status FROM outbox_event WHERE id = ?::uuid", eventId.toString());
    }
    assertThat(status).isEqualTo("SENT");
  }

  private UUID appendStock(UUID tenantId) {
    Map<String, Object> item =
        Map.of(
            "listing_sku_id",
            "tsf_sku_7781",
            "seller_sku",
            "TSHIRT-BLK-M",
            "available",
            18,
            "stock_version",
            1042);
    UUID[] eventId = new UUID[1];
    TenantContext.set(tenantId, null);
    try {
      new TransactionTemplate(transactions)
          .executeWithoutResult(
              tx ->
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
    return eventId[0];
  }

  private static ObjectNode membership(String eventId, String shopId) {
    ObjectNode data = JSON.createObjectNode();
    data.put("name", "Local Shop");
    data.put("tier", "PRO");
    data.put("status", "ACTIVE");
    data.put("ent_ver", 1);
    data.put("expires_at", "2027-01-01T00:00:00Z");
    ObjectNode event = JSON.createObjectNode();
    event.put("event_id", eventId);
    event.put("event_type", "membership.changed");
    event.put("schema_version", 1);
    event.put("occurred_at", Instant.now().truncatedTo(ChronoUnit.SECONDS).toString());
    event.put("tsf_shop_id", shopId);
    event.put("aggregate_id", shopId);
    event.set("data", data);
    return event;
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
    String bound = mock.getEnvironment().getProperty("local.server.port");
    if (bound == null || bound.isBlank() || "0".equals(bound)) {
      throw new IllegalStateException("mock-tsf did not bind a port");
    }
    return Integer.parseInt(bound);
  }

  private static String mockBase() {
    return "http://127.0.0.1:" + mockPort();
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
}
