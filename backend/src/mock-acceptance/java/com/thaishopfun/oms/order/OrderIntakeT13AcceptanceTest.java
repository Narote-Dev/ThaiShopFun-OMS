package com.thaishopfun.oms.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.thaishopfun.mocktsf.OmsEndpoint;
import com.thaishopfun.mocktsf.idp.TokenIssuer;
import com.thaishopfun.oms.auth.AuthTestSupport;
import com.thaishopfun.oms.auth.UuidV7;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** T13 intake follow-ups (UNKNOWN_SKU, bundle paid note, SHADOW shadow_diff). */
@ActiveProfiles("test")
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"spring.main.allow-bean-definition-overriding=true"})
@Import(OrderIntakeT12ScenariosAcceptanceTest.IntakeTestConfig.class)
class OrderIntakeT13AcceptanceTest {

  private static final String INBOX_SECRET = "dev-inbox-hmac-secret";
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final HttpClient HTTP =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
  private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(20);

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
              + "order_recipient, sales_order, stock_reservation, inventory_ledger, inventory, "
              + "idempotency_key, reconciliation_issue, shadow_diff CASCADE");
      statement.execute("SET session_replication_role = DEFAULT");
    }
  }

  @Test
  void componentlessBundlePaidKeepsBundleNoteAndSkipsReservation() throws Exception {
    StockFixture.Shop shop = fixture.shop("ACTIVE");
    String shopId = fixture.tsfShopId(shop);
    UUID account = fixture.tsfChannelAccount(shop, "ACTIVE", "CONNECTED");
    UUID bundle = fixture.componentlessBundle(shop);
    fixture.channelListing(shop, account, "L-t13-bnd", bundle, true);
    String externalOrderId = "TSF-T13-BND-" + UUID.randomUUID();
    ingest(
        OrderIntakeScenarioSupport.orderCreated(
            JSON,
            externalOrderId,
            shopId,
            UuidV7.generate().toString(),
            "PREPAID",
            "L-t13-bnd",
            1,
            1));
    assertThat(worker.processAvailable(10)).isEqualTo(1);
    ingest(OrderIntakeScenarioSupport.orderPaid(JSON, externalOrderId, shopId, 2));
    assertThat(worker.processAvailable(10)).isEqualTo(1);
    assertThat(
            fixture.inTenant(
                shop.tenant(),
                () ->
                    jdbc.queryForObject(
                        "SELECT hold_note FROM sales_order WHERE external_order_id = ?",
                        String.class,
                        externalOrderId)))
        .isEqualTo("bundle has no components");
    assertThat(
            fixture.inTenant(
                shop.tenant(),
                () ->
                    jdbc.queryForObject(
                        "SELECT hold_reason FROM sales_order WHERE external_order_id = ?",
                        String.class,
                        externalOrderId)))
        .isEqualTo("OUT_OF_STOCK");
    assertThat(
            fixture.inTenant(
                shop.tenant(),
                () ->
                    jdbc.queryForObject(
                        "SELECT fulfillment_status FROM sales_order WHERE external_order_id = ?",
                        String.class,
                        externalOrderId)))
        .isEqualTo("UNFULFILLED");
    assertThat(
            fixture.inTenant(
                shop.tenant(),
                () ->
                    jdbc.queryForObject(
                        """
                        SELECT count(*) FROM stock_reservation
                        WHERE owner_type = 'ORDER' AND status = 'ACTIVE'
                        """,
                        Long.class)))
        .isZero();
  }

  @Test
  void shadowComponentlessBundleWritesSingleOrderShadowDiff() throws Exception {
    StockFixture.Shop shop = fixture.shop("ACTIVE");
    String shopId = fixture.tsfShopId(shop);
    UUID account = fixture.tsfChannelAccount(shop, "SHADOW", "CONNECTED");
    UUID bundle = fixture.componentlessBundle(shop);
    fixture.channelListing(shop, account, "L-t13-sh", bundle, true);
    String externalOrderId = "TSF-T13-SH-" + UUID.randomUUID();
    ObjectNode created =
        OrderIntakeScenarioSupport.orderCreated(
            JSON, externalOrderId, shopId, UuidV7.generate().toString(), "COD", "L-t13-sh", 1, 1);
    ingest(created);
    assertThat(worker.processAvailable(10)).isEqualTo(1);
    assertThat(
            fixture.inTenant(
                shop.tenant(),
                () ->
                    jdbc.queryForObject(
                        "SELECT count(*) FROM shadow_diff WHERE kind = 'ORDER' AND ref = ?",
                        Long.class,
                        externalOrderId)))
        .isEqualTo(1);
    JsonNode diff =
        fixture.inTenant(
            shop.tenant(),
            () ->
                JSON.readTree(
                    jdbc.queryForObject(
                        "SELECT oms_value::text FROM shadow_diff WHERE ref = ?",
                        String.class,
                        externalOrderId)));
    assertThat(diff.path("reason").asString()).isEqualTo("BUNDLE_WITHOUT_COMPONENTS");
    assertThat(diff.path("bundle_skus").size()).isEqualTo(1);

    ingest(
        OrderIntakeScenarioSupport.orderCreated(
            JSON, externalOrderId, shopId, UuidV7.generate().toString(), "COD", "L-t13-sh", 1, 2));
    assertThat(worker.processAvailable(10)).isEqualTo(1);
    assertThat(
            fixture.inTenant(
                shop.tenant(),
                () ->
                    jdbc.queryForObject(
                        "SELECT count(*) FROM shadow_diff WHERE kind = 'ORDER' AND ref = ?",
                        Long.class,
                        externalOrderId)))
        .isEqualTo(1);
  }

  @Test
  void activeModeComponentlessBundleHasHoldWithoutShadowDiff() throws Exception {
    StockFixture.Shop shop = fixture.shop("ACTIVE");
    String shopId = fixture.tsfShopId(shop);
    UUID account = fixture.tsfChannelAccount(shop, "ACTIVE", "CONNECTED");
    UUID bundle = fixture.componentlessBundle(shop);
    fixture.channelListing(shop, account, "L-t13-act", bundle, true);
    String externalOrderId = "TSF-T13-ACT-" + UUID.randomUUID();
    ingest(
        OrderIntakeScenarioSupport.orderCreated(
            JSON, externalOrderId, shopId, UuidV7.generate().toString(), "COD", "L-t13-act", 1, 1));
    assertThat(worker.processAvailable(10)).isEqualTo(1);
    assertThat(
            fixture.inTenant(
                shop.tenant(),
                () ->
                    jdbc.queryForObject(
                        "SELECT count(*) FROM shadow_diff WHERE ref = ?",
                        Long.class,
                        externalOrderId)))
        .isZero();
    assertThat(
            fixture.inTenant(
                shop.tenant(),
                () ->
                    jdbc.queryForObject(
                        "SELECT hold_reason FROM sales_order WHERE external_order_id = ?",
                        String.class,
                        externalOrderId)))
        .isEqualTo("OUT_OF_STOCK");
  }

  @Test
  void controlModeComponentlessBundleHasHoldWithoutShadowDiff() throws Exception {
    StockFixture.Shop shop = fixture.shop("ACTIVE");
    String shopId = fixture.tsfShopId(shop);
    UUID account = fixture.tsfChannelAccount(shop, "CONTROL", "CONNECTED");
    UUID bundle = fixture.componentlessBundle(shop);
    fixture.channelListing(shop, account, "L-t13-ctl", bundle, true);
    String externalOrderId = "TSF-T13-CTL-" + UUID.randomUUID();
    ingest(
        OrderIntakeScenarioSupport.orderCreated(
            JSON, externalOrderId, shopId, UuidV7.generate().toString(), "COD", "L-t13-ctl", 1, 1));
    assertThat(worker.processAvailable(10)).isEqualTo(1);
    assertThat(
            fixture.inTenant(
                shop.tenant(),
                () ->
                    jdbc.queryForObject(
                        "SELECT count(*) FROM shadow_diff WHERE ref = ?",
                        Long.class,
                        externalOrderId)))
        .isZero();
  }

  @Test
  void observeModeComponentlessBundleUnchanged() throws Exception {
    StockFixture.Shop shop = fixture.shop("ACTIVE");
    String shopId = fixture.tsfShopId(shop);
    UUID account = fixture.tsfChannelAccount(shop, "OBSERVE", "CONNECTED");
    UUID bundle = fixture.componentlessBundle(shop);
    fixture.channelListing(shop, account, "L-t13-obs", bundle, true);
    String externalOrderId = "TSF-T13-OBS-" + UUID.randomUUID();
    ingest(
        OrderIntakeScenarioSupport.orderCreated(
            JSON, externalOrderId, shopId, UuidV7.generate().toString(), "COD", "L-t13-obs", 1, 1));
    assertThat(worker.processAvailable(10)).isEqualTo(1);
    assertThat(
            fixture.inTenant(
                shop.tenant(),
                () ->
                    jdbc.queryForObject(
                        "SELECT count(*) FROM shadow_diff WHERE ref = ?",
                        Long.class,
                        externalOrderId)))
        .isZero();
    assertThat(
            fixture.inTenant(
                shop.tenant(),
                () ->
                    jdbc.queryForObject(
                        "SELECT hold_reason FROM sales_order WHERE external_order_id = ?",
                        String.class,
                        externalOrderId)))
        .isEqualTo("NONE");
  }

  private void ingest(ObjectNode event) throws Exception {
    byte[] body = JSON.writeValueAsBytes(event);
    String eventId = event.path("event_id").asString();
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/internal/v1/events"))
            .timeout(HTTP_TIMEOUT)
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
