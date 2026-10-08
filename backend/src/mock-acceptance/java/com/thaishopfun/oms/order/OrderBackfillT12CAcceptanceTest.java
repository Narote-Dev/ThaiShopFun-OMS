package com.thaishopfun.oms.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.thaishopfun.mocktsf.OmsEndpoint;
import com.thaishopfun.mocktsf.idp.TokenIssuer;
import com.thaishopfun.oms.auth.AuthTestSupport;
import com.thaishopfun.oms.inbox.InboxWorker;
import com.thaishopfun.oms.order.backfill.OrderBackfillJob;
import com.thaishopfun.oms.order.backfill.OrderBackfillProperties;
import com.thaishopfun.oms.stock.StockFixture;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@ActiveProfiles("test")
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.main.allow-bean-definition-overriding=true",
      "oms.order.backfill.enabled=false",
      "oms.inbox.worker-enabled=false"
    })
@Import(OrderIntakeT12ScenariosAcceptanceTest.IntakeTestConfig.class)
class OrderBackfillT12CAcceptanceTest {

  private static final String INBOX_SECRET = "dev-inbox-hmac-secret";
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final HttpClient HTTP =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

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
    registry.add("oms.tsf.base-url", () -> "http://127.0.0.1:" + mockPort);
    registry.add("oms.tsf.token-uri", () -> "http://127.0.0.1:" + mockPort + "/tsf-idp/token");
    registry.add("oms.tsf.client-id", () -> "oms-service");
    registry.add("oms.tsf.client-secret", () -> "dev-oms-service-secret");
    registry.add("oms.tsf.audience", () -> "tsf-internal");
  }

  @LocalServerPort private int port;
  @Autowired OrderBackfillJob backfill;
  @Autowired OrderBackfillProperties backfillProperties;
  @Autowired InboxWorker worker;
  @Autowired JdbcTemplate jdbc;
  @Autowired PlatformTransactionManager transactions;

  StockFixture fixture;

  @BeforeEach
  void setup() throws Exception {
    OrderIntakeMockRuntime.mock().getBean(OmsEndpoint.class).setBaseUrl("http://127.0.0.1:" + port);
    fixture = new StockFixture(jdbc, transactions);
    try (Connection admin = AuthTestSupport.admin();
        var statement = admin.createStatement()) {
      statement.execute("SET session_replication_role = replica");
      statement.execute(
          "TRUNCATE TABLE outbox_event, inbox_event, order_status_history, order_line, "
              + "order_recipient, sales_order, stock_reservation, inventory_ledger, inventory, "
              + "sync_cursor, reconciliation_issue CASCADE");
      statement.execute("SET session_replication_role = DEFAULT");
    }
    postMock("/control/webhooks", "{\"enabled\":false}");
  }

  @AfterEach
  void restoreWebhooks() throws Exception {
    postMock("/control/webhooks", "{\"enabled\":true}");
  }

  @Test
  void defaultIntervalIsFifteenMinutes() {
    assertThat(backfillProperties.getInterval()).isEqualTo(Duration.ofMinutes(15));
  }

  @Test
  void webhooksOffBulkOrdersBackfillCreatesAllWithoutDuplicates() throws Exception {
    StockFixture.Shop shop = fixture.shop("ACTIVE");
    String shopId = fixture.tsfShopId(shop);
    UUID account = fixture.tsfChannelAccount(shop, "ACTIVE", "CONNECTED");
    UUID sku = fixture.sku(shop, 500);
    fixture.channelListing(shop, account, "tsf_sku_7781", sku, true);
    postMock(
        "/control/orders/bulk",
        "{\"count\":50,\"payment\":\"PREPAID\",\"paid\":true,\"shop_id\":\"" + shopId + "\"}");
    backfill.runOnceForTenant(shop.tenant());
    long count =
        fixture.inTenant(
            shop.tenant(),
            () -> jdbc.queryForObject("SELECT count(*) FROM sales_order", Long.class));
    assertThat(count).isEqualTo(50);
    backfill.runOnceForTenant(shop.tenant());
    long again =
        fixture.inTenant(
            shop.tenant(),
            () -> jdbc.queryForObject("SELECT count(*) FROM sales_order", Long.class));
    assertThat(again).isEqualTo(50);
  }

  @Test
  void gapRefetchAppliesPaidSnapshot() throws Exception {
    StockFixture.Shop shop = fixture.shop("ACTIVE");
    String shopId = fixture.tsfShopId(shop);
    UUID account = fixture.tsfChannelAccount(shop, "ACTIVE", "CONNECTED");
    UUID sku = fixture.sku(shop, 10);
    fixture.channelListing(shop, account, "L-gap", sku, true);
    String orderId = "TSF-GAP-" + UUID.randomUUID();
    postMock(
        "/control/orders/register",
        "{\"shop_id\":\"" + shopId + "\",\"order_id\":\"" + orderId + "\"}");
    ingest(
        OrderIntakeScenarioSupport.orderCreated(
            JSON, orderId, shopId, UUID.randomUUID().toString(), "PREPAID", "L-gap", 1, 1));
    assertThat(worker.processAvailable(5)).isEqualTo(1);
    postMock("/control/orders/" + orderId + "/mark-paid", "{\"aggregate_version\":3}");
    ingest(OrderIntakeScenarioSupport.orderPaid(JSON, orderId, shopId, 3));
    assertThat(worker.processAvailable(5)).isEqualTo(1);
    String payment =
        fixture.inTenant(
            shop.tenant(),
            () ->
                jdbc.queryForObject(
                    "SELECT payment_status FROM sales_order WHERE external_order_id = ?",
                    String.class,
                    orderId));
    assertThat(payment).isEqualTo("PAID");
  }

  private void ingest(ObjectNode event) throws Exception {
    MockTsfCatalogSync.note(event);
    byte[] body = JSON.writeValueAsBytes(event);
    String eventId = event.path("event_id").asString();
    String now = Long.toString(Instant.now().getEpochSecond());
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/internal/v1/events"))
            .timeout(Duration.ofSeconds(20))
            .header("Content-Type", "application/json")
            .header("X-Event-Id", eventId)
            .header("X-Signature", sign(INBOX_SECRET, now, body))
            .header("Authorization", "Bearer " + tsfToken())
            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
            .build();
    HttpResponse<String> response =
        HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    assertThat(response.statusCode()).isEqualTo(202);
  }

  private static String tsfToken() {
    return OrderIntakeMockRuntime.mock().getBean(TokenIssuer.class).tsfServiceToken();
  }

  private static String sign(String secret, String timestamp, byte[] body) throws Exception {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    mac.update((timestamp + ".").getBytes(StandardCharsets.UTF_8));
    mac.update(body);
    return "t=" + timestamp + ",v1=" + HexFormat.of().formatHex(mac.doFinal());
  }

  private void postMock(String path, String body) throws Exception {
    int mockPort = OrderIntakeMockRuntime.mockPort();
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + mockPort + path))
            .timeout(Duration.ofSeconds(20))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build();
    HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isBetween(200, 299);
  }
}
