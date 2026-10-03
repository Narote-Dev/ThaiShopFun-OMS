package com.thaishopfun.oms.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.thaishopfun.mocktsf.OmsEndpoint;
import com.thaishopfun.oms.auth.AuthTestSupport;
import com.thaishopfun.oms.inbox.InboxWorker;
import com.thaishopfun.oms.stock.StockFixture;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** T13 B1: orphan reconciliation count stays distinct across defer-cap retries. */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class InboxOrphanDeferCapTest {

  private static final String INBOX_SECRET = "dev-inbox-hmac-secret";
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final HttpClient HTTP =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
  private static final AtomicReference<String> MAX_DEFER = new AtomicReference<>("1h");

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    OrderIntakeMockRuntime.startMock();
    AuthTestSupport.register(registry);
    int mockPort = OrderIntakeMockRuntime.mockPort();
    registry.add("oms.security.issuer", OrderIntakeMockRuntime::issuer);
    registry.add(
        "oms.security.jwks-uri",
        () -> "http://127.0.0.1:" + mockPort + "/tsf-idp/.well-known/jwks.json");
    registry.add("oms.security.internal-client-ids", () -> "tsf,tsf-checkout");
    registry.add("oms.inbox.hmac-secrets", () -> INBOX_SECRET);
    registry.add("oms.inbox.jitter-ratio", () -> "0");
    registry.add("oms.inbox.max-defer", () -> MAX_DEFER.get());
    registry.add("oms.outbox.publisher-enabled", () -> "false");
  }

  @LocalServerPort private int port;

  @Autowired InboxWorker worker;
  @Autowired JdbcTemplate jdbc;
  @Autowired PlatformTransactionManager transactions;

  StockFixture fixture;

  @BeforeEach
  void setup() throws Exception {
    MAX_DEFER.set("1h");
    OrderIntakeMockRuntime.mock().getBean(OmsEndpoint.class).setBaseUrl("http://127.0.0.1:" + port);
    fixture = new StockFixture(jdbc, transactions);
    try (Connection admin = AuthTestSupport.admin();
        var statement = admin.createStatement()) {
      statement.execute("SET session_replication_role = replica");
      statement.execute("TRUNCATE TABLE inbox_event, reconciliation_issue CASCADE");
      statement.execute("SET session_replication_role = DEFAULT");
    }
  }

  @Test
  void orphanCapCrossingRetriesDoNotInflateDistinctCount() throws Exception {
    StockFixture.Shop shop = fixture.shop("ACTIVE");
    String shopId = fixture.tsfShopId(shop);
    fixture.tsfChannelAccount(shop, "ACTIVE", "CONNECTED");
    List<String> eventIds = new ArrayList<>();
    for (int i = 0; i < 105; i++) {
      String externalOrderId = "TSF-ORPH-" + i;
      ObjectNode paid = OrderIntakeScenarioSupport.orderPaid(JSON, externalOrderId, shopId, i + 1);
      ingest(paid);
      eventIds.add(paid.path("event_id").asString());
    }
    assertThat(worker.processAvailable(200)).isEqualTo(105);

    Instant old = Instant.now().minus(25, ChronoUnit.HOURS);
    for (String eventId : eventIds) {
      backdateReceivedAt(eventId, old);
      rewind(eventId);
    }
    for (int round = 0; round < 3; round++) {
      for (String eventId : eventIds) {
        rewind(eventId);
      }
      worker.processAvailable(200);
    }

    JsonNode details =
        fixture.inTenant(
            shop.tenant(),
            () ->
                JSON.readTree(
                    jdbc.queryForObject(
                        """
                        SELECT details::text FROM reconciliation_issue
                        WHERE rule = 'ORDER_EVENT_WITHOUT_ORDER' AND status = 'OPEN'
                        """,
                        String.class)));
    assertThat(details.path("count").asInt()).isEqualTo(105);
    assertThat(details.path("events").size()).isEqualTo(100);
  }

  private void ingest(ObjectNode event) throws Exception {
    byte[] body = JSON.writeValueAsBytes(event);
    String eventId = event.path("event_id").asString();
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/internal/v1/events"))
            .timeout(Duration.ofSeconds(20))
            .header("Content-Type", "application/json")
            .header("X-Event-Id", eventId)
            .header("X-Signature", sign(INBOX_SECRET, now(), body))
            .header("Authorization", "Bearer " + tsfToken())
            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
            .build();
    HttpResponse<String> response =
        HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    assertThat(response.statusCode()).isEqualTo(202);
  }

  private void backdateReceivedAt(String eventId, Instant receivedAt) throws Exception {
    try (Connection admin = AuthTestSupport.admin();
        PreparedStatement statement =
            admin.prepareStatement("UPDATE inbox_event SET received_at = ? WHERE event_id = ?")) {
      statement.setObject(1, OffsetDateTime.ofInstant(receivedAt, ZoneOffset.UTC));
      statement.setString(2, eventId);
      assertThat(statement.executeUpdate()).isEqualTo(1);
    }
  }

  private void rewind(String eventId) throws Exception {
    try (Connection admin = AuthTestSupport.admin();
        PreparedStatement statement =
            admin.prepareStatement(
                "UPDATE inbox_event SET next_attempt_at = now() - interval '1 second', "
                    + "status = CASE WHEN status = 'FAILED' THEN 'RECEIVED' ELSE status END "
                    + "WHERE event_id = ?")) {
      statement.setString(1, eventId);
      statement.executeUpdate();
    }
  }

  private static String tsfToken() {
    return AuthTestSupport.token(
        "tsf",
        "shop",
        "ACTIVE",
        null,
        1,
        "oms-internal",
        Instant.now().plusSeconds(600),
        java.util.List.of(),
        "SERVICE");
  }

  private static String sign(String secret, String timestamp, byte[] body) throws Exception {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    mac.update((timestamp + ".").getBytes(StandardCharsets.UTF_8));
    mac.update(body);
    return "t=" + timestamp + ",v1=" + HexFormat.of().formatHex(mac.doFinal());
  }

  private static String now() {
    return Long.toString(Instant.now().getEpochSecond());
  }
}
