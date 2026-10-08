package com.thaishopfun.oms.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.thaishopfun.mocktsf.OmsEndpoint;
import com.thaishopfun.mocktsf.idp.TokenIssuer;
import com.thaishopfun.oms.auth.AuthTestSupport;
import com.thaishopfun.oms.inbox.InboxWorker;
import com.thaishopfun.oms.invariant.VerifyInvariants;
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
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
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

/** T13 A6: out-of-order inbox scenarios (subset not covered elsewhere). */
@ActiveProfiles("test")
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"spring.main.allow-bean-definition-overriding=true"})
@Import(OrderIntakeT12ScenariosAcceptanceTest.IntakeTestConfig.class)
@VerifyInvariants
class OrderIntakeT13OutOfOrderAcceptanceTest {

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
    registry.add("oms.inbox.jitter-ratio", () -> "0");
    registry.add("oms.outbox.publisher-enabled", () -> "false");
    registry.add("oms.tsf.base-url", () -> "http://127.0.0.1:" + mockPort);
    registry.add("oms.tsf.token-uri", () -> "http://127.0.0.1:" + mockPort + "/tsf-idp/token");
    registry.add("oms.tsf.client-id", () -> "oms-service");
    registry.add("oms.tsf.client-secret", () -> "dev-oms-service-secret");
    registry.add("oms.tsf.audience", () -> "tsf-internal");
  }

  @LocalServerPort private int port;
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
              + "order_recipient, sales_order, stock_reservation, reconciliation_issue CASCADE");
      statement.execute("SET session_replication_role = DEFAULT");
    }
  }

  @Test
  void cancelAfterShippedLeavesOrderAndOpensIssue() throws Exception {
    StockFixture.Shop shop = fixture.shop("ACTIVE");
    String shopId = fixture.tsfShopId(shop);
    UUID account = fixture.tsfChannelAccount(shop, "ACTIVE", "CONNECTED");
    UUID sku = fixture.sku(shop, 5);
    fixture.channelListing(shop, account, "L-t13-shp", sku, true);
    String externalOrderId = "TSF-T13-SHP-" + UUID.randomUUID();
    ingest(
        OrderIntakeScenarioSupport.orderCreated(
            JSON, externalOrderId, shopId, UUID.randomUUID().toString(), "COD", "L-t13-shp", 1, 1));
    assertThat(worker.processAvailable(10)).isEqualTo(1);
    UUID orderId =
        fixture.inTenant(
            shop.tenant(),
            () ->
                jdbc.queryForObject(
                    "SELECT id FROM sales_order WHERE external_order_id = ?",
                    UUID.class,
                    externalOrderId));
    fixture.inTenant(
        shop.tenant(),
        () ->
            jdbc.update(
                "UPDATE sales_order SET fulfillment_status = 'SHIPPED' WHERE id = ?", orderId));
    ObjectNode cancel = OrderIntakeScenarioSupport.orderCancelled(JSON, externalOrderId, shopId, 2);
    ingest(cancel);
    assertThat(worker.processAvailable(10)).isEqualTo(1);
    assertThat(
            fixture.inTenant(
                shop.tenant(),
                () ->
                    jdbc.queryForObject(
                        "SELECT order_status FROM sales_order WHERE id = ?",
                        String.class,
                        orderId)))
        .isEqualTo("ACTIVE");
    assertThat(
            fixture.inTenant(
                shop.tenant(),
                () ->
                    jdbc.queryForObject(
                        "SELECT fulfillment_status FROM sales_order WHERE id = ?",
                        String.class,
                        orderId)))
        .isEqualTo("SHIPPED");
    assertThat(
            fixture.inTenant(
                shop.tenant(),
                () ->
                    jdbc.queryForObject(
                        """
                        SELECT count(*) FROM reconciliation_issue
                        WHERE rule = 'CANCEL_AFTER_SHIPPED' AND order_id = ? AND status = 'OPEN'
                        """,
                        Long.class,
                        orderId)))
        .isEqualTo(1);
  }

  @Test
  void staleAggregateVersionIsSkipped() throws Exception {
    StockFixture.Shop shop = fixture.shop("ACTIVE");
    String shopId = fixture.tsfShopId(shop);
    UUID account = fixture.tsfChannelAccount(shop, "ACTIVE", "CONNECTED");
    UUID sku = fixture.sku(shop, 10);
    fixture.channelListing(shop, account, "L-t13-stale", sku, true);
    String externalOrderId = "TSF-T13-STALE-" + UUID.randomUUID();
    ingest(
        OrderIntakeScenarioSupport.orderCreated(
            JSON,
            externalOrderId,
            shopId,
            UUID.randomUUID().toString(),
            "PREPAID",
            "L-t13-stale",
            1,
            1));
    assertThat(worker.processAvailable(10)).isEqualTo(1);

    ObjectNode fresh = OrderIntakeScenarioSupport.orderUpdated(JSON, externalOrderId, shopId, 2);
    ObjectNode recipientA = JSON.createObjectNode();
    recipientA.put("name", "Recipient A");
    recipientA.put("phone", "0811111111");
    ObjectNode addressA = JSON.createObjectNode();
    addressA.put("line1", "line a");
    addressA.put("district", "district");
    addressA.put("province", "Province-A");
    addressA.put("postcode", "10110");
    recipientA.set("address", addressA);
    ((ObjectNode) fresh.path("data")).set("recipient", recipientA);
    ingest(fresh);
    assertThat(worker.processAvailable(10)).isEqualTo(1);

    ObjectNode stale = OrderIntakeScenarioSupport.orderUpdated(JSON, externalOrderId, shopId, 1);
    stale.put("event_id", "evt-stale-upd-" + UUID.randomUUID());
    ObjectNode recipientB = JSON.createObjectNode();
    recipientB.put("name", "Recipient B");
    recipientB.put("phone", "0822222222");
    ObjectNode addressB = JSON.createObjectNode();
    addressB.put("line1", "line b");
    addressB.put("district", "district");
    addressB.put("province", "Province-B");
    addressB.put("postcode", "10110");
    recipientB.set("address", addressB);
    ((ObjectNode) stale.path("data")).set("recipient", recipientB);
    String staleEventId = stale.path("event_id").asString();
    ingest(stale);
    assertThat(worker.processAvailable(10)).isEqualTo(1);
    assertThat(text("SELECT status FROM inbox_event WHERE event_id = ?", staleEventId))
        .isEqualTo("PROCESSED");
    String province =
        fixture.inTenant(
            shop.tenant(),
            () ->
                jdbc.queryForObject(
                    """
                    SELECT province FROM order_recipient
                    WHERE order_id = (SELECT id FROM sales_order WHERE external_order_id = ?)
                    """,
                    String.class,
                    externalOrderId));
    assertThat(province).isEqualTo("Province-A");
  }

  @Test
  void duplicatePaidWithNewEventIdAddsNoPaymentHistory() throws Exception {
    StockFixture.Shop shop = fixture.shop("ACTIVE");
    String shopId = fixture.tsfShopId(shop);
    UUID account = fixture.tsfChannelAccount(shop, "ACTIVE", "CONNECTED");
    UUID sku = fixture.sku(shop, 4);
    fixture.channelListing(shop, account, "L-t13-dup", sku, true);
    String externalOrderId = "TSF-T13-DUP-" + UUID.randomUUID();
    ingest(
        OrderIntakeScenarioSupport.orderCreated(
            JSON,
            externalOrderId,
            shopId,
            UUID.randomUUID().toString(),
            "PREPAID",
            "L-t13-dup",
            1,
            1));
    assertThat(worker.processAvailable(10)).isEqualTo(1);
    ObjectNode paid = OrderIntakeScenarioSupport.orderPaid(JSON, externalOrderId, shopId, 2);
    ingest(paid);
    assertThat(worker.processAvailable(10)).isEqualTo(1);
    long historyAfterFirstPaid =
        fixture.inTenant(
            shop.tenant(),
            () ->
                jdbc.queryForObject(
                    """
                    SELECT count(*) FROM order_status_history h
                    JOIN sales_order o ON o.id = h.order_id
                    WHERE o.external_order_id = ? AND h.dimension = 'PAYMENT'
                    """,
                    Long.class,
                    externalOrderId));
    assertThat(historyAfterFirstPaid).isEqualTo(1);

    ObjectNode paidDup = OrderIntakeScenarioSupport.orderPaid(JSON, externalOrderId, shopId, 3);
    paidDup.put("event_id", "evt-dup-paid-" + UUID.randomUUID());
    ingest(paidDup);
    assertThat(worker.processAvailable(10)).isEqualTo(1);
    long historyAfterDup =
        fixture.inTenant(
            shop.tenant(),
            () ->
                jdbc.queryForObject(
                    """
                    SELECT count(*) FROM order_status_history h
                    JOIN sales_order o ON o.id = h.order_id
                    WHERE o.external_order_id = ? AND h.dimension = 'PAYMENT'
                    """,
                    Long.class,
                    externalOrderId));
    assertThat(historyAfterDup).isEqualTo(1);
  }

  private void ingest(ObjectNode event) throws Exception {
    MockTsfCatalogSync.note(event);
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
    assertThat(
            HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .statusCode())
        .isEqualTo(202);
  }

  private static String text(String sql, String eventId) throws Exception {
    try (Connection admin = AuthTestSupport.admin();
        PreparedStatement statement = admin.prepareStatement(sql)) {
      statement.setString(1, eventId);
      try (var rows = statement.executeQuery()) {
        assertThat(rows.next()).isTrue();
        return rows.getString(1);
      }
    }
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

  private static String now() {
    return Long.toString(Instant.now().getEpochSecond());
  }
}
