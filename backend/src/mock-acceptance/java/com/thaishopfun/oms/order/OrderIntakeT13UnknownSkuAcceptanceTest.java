package com.thaishopfun.oms.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;

import com.thaishopfun.mocktsf.OmsEndpoint;
import com.thaishopfun.mocktsf.idp.TokenIssuer;
import com.thaishopfun.oms.auth.AuthTestSupport;
import com.thaishopfun.oms.auth.UuidV7;
import com.thaishopfun.oms.inbox.InboxWorker;
import com.thaishopfun.oms.stock.ReservationEngine;
import com.thaishopfun.oms.stock.ReserveItem;
import com.thaishopfun.oms.stock.StockError;
import com.thaishopfun.oms.stock.StockFixture;
import com.thaishopfun.oms.stock.StockOperationException;
import com.thaishopfun.oms.stock.StockOwner;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@ActiveProfiles("test")
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"spring.main.allow-bean-definition-overriding=true"})
@Import(OrderIntakeT12ScenariosAcceptanceTest.IntakeTestConfig.class)
class OrderIntakeT13UnknownSkuAcceptanceTest {

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
  }

  @LocalServerPort private int port;
  @Autowired InboxWorker worker;
  @Autowired JdbcTemplate jdbc;
  @Autowired PlatformTransactionManager transactions;
  @MockitoSpyBean ReservationEngine reservationEngine;

  StockFixture fixture;

  @BeforeEach
  void setup() throws Exception {
    OrderIntakeMockRuntime.mock().getBean(OmsEndpoint.class).setBaseUrl("http://127.0.0.1:" + port);
    fixture = new StockFixture(jdbc, transactions);
    doCallRealMethod()
        .when(reservationEngine)
        .adoptForOrder(any(), any(), anyList(), any(), anyString());
    doCallRealMethod().when(reservationEngine).ensureOrderHold(any(), anyList(), anyString());
    doCallRealMethod().when(reservationEngine).reserve(any(), anyList(), anyString());
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
  void unknownSkuAtCreatedSetsSkuNotMappedWithoutDeadInbox() throws Exception {
    StockFixture.Shop shop = fixture.shop("ACTIVE");
    String shopId = fixture.tsfShopId(shop);
    UUID account = fixture.tsfChannelAccount(shop, "ACTIVE", "CONNECTED");
    UUID sku = fixture.sku(shop, 5);
    fixture.channelListing(shop, account, "L-unk-c", sku, true);
    doThrow(new StockOperationException(StockError.UNKNOWN_SKU, "injected"))
        .when(reservationEngine)
        .adoptForOrder(any(), any(), anyList(), any(), anyString());

    String externalOrderId = "TSF-T13-UNK-C-" + UUID.randomUUID();
    ObjectNode created =
        OrderIntakeScenarioSupport.orderCreated(
            JSON, externalOrderId, shopId, UuidV7.generate().toString(), "COD", "L-unk-c", 1, 1);
    ingest(created);
    assertThat(worker.processAvailable(10)).isEqualTo(1);
    String eventId = created.path("event_id").asString();
    assertThat(text("SELECT status FROM inbox_event WHERE event_id = ?", eventId))
        .isEqualTo("PROCESSED");
    assertThat(text("SELECT attempts FROM inbox_event WHERE event_id = ?", eventId)).isEqualTo("1");
    assertThat(text("SELECT last_error FROM inbox_event WHERE event_id = ?", eventId)).isNull();
    UUID orderId =
        fixture.inTenant(
            shop.tenant(),
            () ->
                jdbc.queryForObject(
                    "SELECT id FROM sales_order WHERE external_order_id = ?",
                    UUID.class,
                    externalOrderId));
    assertThat(
            fixture.inTenant(
                shop.tenant(),
                () ->
                    jdbc.queryForObject(
                        "SELECT count(*) FROM order_line WHERE order_id = ?", Long.class, orderId)))
        .isEqualTo(1);
    assertThat(
            fixture.inTenant(
                shop.tenant(),
                () ->
                    jdbc.queryForObject(
                        "SELECT hold_reason FROM sales_order WHERE id = ?", String.class, orderId)))
        .isEqualTo("SKU_NOT_MAPPED");
    assertThat(
            fixture.inTenant(
                shop.tenant(),
                () ->
                    jdbc.queryForObject(
                        """
                        SELECT count(*) FROM order_status_history
                        WHERE order_id = ? AND dimension = 'HOLD'
                        """,
                        Long.class,
                        orderId)))
        .isEqualTo(1);
    assertThat(
            fixture.inTenant(
                shop.tenant(),
                () ->
                    jdbc.queryForObject(
                        """
                        SELECT count(*) FROM stock_reservation
                        WHERE owner_type = 'ORDER' AND owner_ref = ?::text
                        """,
                        Long.class,
                        orderId.toString())))
        .isZero();

    doCallRealMethod()
        .when(reservationEngine)
        .adoptForOrder(any(), any(), anyList(), any(), anyString());
    fixture.inTenant(
        shop.tenant(),
        () ->
            reservationEngine.reserve(
                StockOwner.order(orderId.toString()),
                List.of(ReserveItem.of(sku, 1)),
                "order.retry:" + UUID.randomUUID()));
  }

  @Test
  void unknownSkuAtPaidSetsSkuNotMappedWithoutFailedRetries() throws Exception {
    StockFixture.Shop shop = fixture.shop("ACTIVE");
    String shopId = fixture.tsfShopId(shop);
    UUID account = fixture.tsfChannelAccount(shop, "ACTIVE", "CONNECTED");
    UUID sku = fixture.sku(shop, 5);
    fixture.channelListing(shop, account, "L-unk-p", sku, true);
    String externalOrderId = "TSF-T13-UNK-P-" + UUID.randomUUID();
    ingest(
        OrderIntakeScenarioSupport.orderCreated(
            JSON,
            externalOrderId,
            shopId,
            UuidV7.generate().toString(),
            "PREPAID",
            "L-unk-p",
            1,
            1));
    assertThat(worker.processAvailable(10)).isEqualTo(1);

    doThrow(new StockOperationException(StockError.UNKNOWN_SKU, "injected"))
        .when(reservationEngine)
        .ensureOrderHold(any(), anyList(), anyString());
    ObjectNode paid = OrderIntakeScenarioSupport.orderPaid(JSON, externalOrderId, shopId, 2);
    ingest(paid);
    assertThat(worker.processAvailable(10)).isEqualTo(1);
    String eventId = paid.path("event_id").asString();
    assertThat(text("SELECT status FROM inbox_event WHERE event_id = ?", eventId))
        .isEqualTo("PROCESSED");
    assertThat(text("SELECT attempts FROM inbox_event WHERE event_id = ?", eventId)).isEqualTo("1");
    assertThat(text("SELECT last_error FROM inbox_event WHERE event_id = ?", eventId)).isNull();
    assertThat(
            fixture.inTenant(
                shop.tenant(),
                () ->
                    jdbc.queryForObject(
                        "SELECT hold_reason FROM sales_order WHERE external_order_id = ?",
                        String.class,
                        externalOrderId)))
        .isEqualTo("SKU_NOT_MAPPED");
  }

  private void ingest(ObjectNode event) throws Exception {
    byte[] body = JSON.writeValueAsBytes(event);
    String eventId = event.path("event_id").asString();
    var request =
        java.net.http.HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + port + "/internal/v1/events"))
            .timeout(Duration.ofSeconds(20))
            .header("Content-Type", "application/json")
            .header("X-Event-Id", eventId)
            .header("X-Signature", sign(INBOX_SECRET, now(), body))
            .header("Authorization", "Bearer " + tsfToken())
            .POST(java.net.http.HttpRequest.BodyPublishers.ofByteArray(body))
            .build();
    assertThat(
            HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .statusCode())
        .isEqualTo(202);
  }

  private static String text(String sql, String eventId) throws Exception {
    try (Connection admin = AuthTestSupport.admin();
        var statement = admin.prepareStatement(sql)) {
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
