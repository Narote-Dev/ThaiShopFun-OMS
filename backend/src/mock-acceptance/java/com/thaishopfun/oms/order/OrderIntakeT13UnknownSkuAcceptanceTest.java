package com.thaishopfun.oms.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;

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
  UUID omitSkuFromCatalog;

  @BeforeEach
  void setup() throws Exception {
    OrderIntakeMockRuntime.mock().getBean(OmsEndpoint.class).setBaseUrl("http://127.0.0.1:" + port);
    fixture = new StockFixture(jdbc, transactions);
    omitSkuFromCatalog = null;
    doCallRealMethod()
        .when(reservationEngine)
        .adoptForOrder(any(), any(), anyList(), any(), anyString());
    doCallRealMethod().when(reservationEngine).ensureOrderHold(any(), anyList(), anyString());
    doCallRealMethod().when(reservationEngine).reserve(any(), anyList(), anyString());
    stubUnknownSkuFromReserveItems();
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
  void unknownSkuAtCreatedUsesPlannerLookupAndIsIdempotentOnRetry() throws Exception {
    StockFixture.Shop shop = fixture.shop("ACTIVE");
    String shopId = fixture.tsfShopId(shop);
    UUID account = fixture.tsfChannelAccount(shop, "ACTIVE", "CONNECTED");
    UUID goodSku = fixture.sku(shop, 8);
    UUID badSku = fixture.sku(shop, 2);
    fixture.channelListing(shop, account, "L-good", goodSku, true);
    fixture.channelListing(shop, account, "L-bad", badSku, true);
    omitSkuFromCatalog = badSku;

    String externalOrderId = "TSF-T13-UNK-2L-" + UUID.randomUUID();
    ObjectNode created =
        OrderIntakeScenarioSupport.orderCreatedTwoLines(
            JSON,
            externalOrderId,
            shopId,
            UuidV7.generate().toString(),
            "COD",
            "L-good",
            "L-bad",
            1,
            1,
            1);
    ingest(created);
    String eventId = created.path("event_id").asString();
    drainWorkerUntilProcessed(eventId);
    assertInboxProcessedOnce(eventId);
    UUID orderId = orderId(shop, externalOrderId);
    assertThat(holdReason(shop, orderId)).isEqualTo("SKU_NOT_MAPPED");
    String holdNote = holdNote(shop, orderId);
    assertThat(holdNote).startsWith("mapped sku not found");
    assertThat(holdNote).contains("L-bad");
    assertThat(holdNote).doesNotContain("L-good");
    assertThat(historyHoldCount(shop, orderId)).isEqualTo(1);
    assertThat(orderReservationCount(shop, orderId)).isZero();

    resetInboxToReceived(eventId);
    drainWorkerUntilProcessed(eventId);
    assertInboxProcessedOnce(eventId);
    assertThat(holdReason(shop, orderId)).isEqualTo("SKU_NOT_MAPPED");
    assertThat(holdNote(shop, orderId)).isEqualTo(holdNote);
    assertThat(historyHoldCount(shop, orderId)).isEqualTo(1);

    omitSkuFromCatalog = null;
    stubUnknownSkuFromReserveItems();
    fixture.inTenant(
        shop.tenant(),
        () ->
            reservationEngine.reserve(
                StockOwner.order(orderId.toString()),
                List.of(ReserveItem.of(goodSku, 1), ReserveItem.of(badSku, 1)),
                "order.retry:" + UUID.randomUUID()));
    assertThat(orderReservationCount(shop, orderId)).isGreaterThan(0);
  }

  @Test
  void unknownSkuAtPaidSetsSkuNotMappedWithHoldHistory() throws Exception {
    StockFixture.Shop shop = fixture.shop("ACTIVE");
    String shopId = fixture.tsfShopId(shop);
    UUID account = fixture.tsfChannelAccount(shop, "ACTIVE", "CONNECTED");
    UUID sku = fixture.sku(shop, 5);
    fixture.channelListing(shop, account, "L-unk-p", sku, true);
    omitSkuFromCatalog = sku;
    String externalOrderId = "TSF-T13-UNK-P-" + UUID.randomUUID();
    ObjectNode created =
        OrderIntakeScenarioSupport.orderCreated(
            JSON,
            externalOrderId,
            shopId,
            UuidV7.generate().toString(),
            "PREPAID",
            "L-unk-p",
            1,
            1);
    ingest(created);
    drainWorkerUntilProcessed(created.path("event_id").asString());

    ObjectNode paid = OrderIntakeScenarioSupport.orderPaid(JSON, externalOrderId, shopId, 2);
    ingest(paid);
    String eventId = paid.path("event_id").asString();
    drainWorkerUntilProcessed(eventId);
    assertInboxProcessedOnce(eventId);
    UUID orderId = orderId(shop, externalOrderId);
    assertThat(holdReason(shop, orderId)).isEqualTo("SKU_NOT_MAPPED");
    assertThat(holdNote(shop, orderId)).contains("L-unk-p");
    assertThat(historyHoldCount(shop, orderId)).isEqualTo(1);
    assertThat(orderReservationCount(shop, orderId)).isZero();
  }

  private void stubUnknownSkuFromReserveItems() {
    doAnswer(
            inv -> {
              List<ReserveItem> items = inv.getArgument(2);
              if (omitSkuFromCatalog != null) {
                for (ReserveItem item : items) {
                  if (omitSkuFromCatalog.equals(item.skuId())) {
                    throw new StockOperationException(
                        StockError.UNKNOWN_SKU, "unknown sku " + omitSkuFromCatalog);
                  }
                }
              }
              return inv.callRealMethod();
            })
        .when(reservationEngine)
        .adoptForOrder(any(), any(), anyList(), any(), anyString());
    doAnswer(
            inv -> {
              List<ReserveItem> items = inv.getArgument(1);
              if (omitSkuFromCatalog != null) {
                for (ReserveItem item : items) {
                  if (omitSkuFromCatalog.equals(item.skuId())) {
                    throw new StockOperationException(
                        StockError.UNKNOWN_SKU, "unknown sku " + omitSkuFromCatalog);
                  }
                }
              }
              return inv.callRealMethod();
            })
        .when(reservationEngine)
        .ensureOrderHold(any(), anyList(), anyString());
  }

  private void drainWorkerUntilProcessed(String eventId) throws Exception {
    for (int i = 0; i < 5; i++) {
      worker.processAvailable(10);
      String status = text("SELECT status FROM inbox_event WHERE event_id = ?", eventId);
      if ("PROCESSED".equals(status)) {
        return;
      }
      if ("DEAD".equals(status)) {
        String lastError = text("SELECT last_error FROM inbox_event WHERE event_id = ?", eventId);
        throw new AssertionError("inbox DEAD: " + lastError);
      }
    }
    throw new AssertionError(
        "inbox not processed: "
            + text("SELECT status FROM inbox_event WHERE event_id = ?", eventId)
            + " last_error="
            + text("SELECT last_error FROM inbox_event WHERE event_id = ?", eventId));
  }

  private void assertInboxProcessedOnce(String eventId) throws Exception {
    assertThat(text("SELECT status FROM inbox_event WHERE event_id = ?", eventId))
        .isEqualTo("PROCESSED");
    assertThat(text("SELECT attempts FROM inbox_event WHERE event_id = ?", eventId)).isEqualTo("1");
    assertThat(text("SELECT last_error FROM inbox_event WHERE event_id = ?", eventId)).isNull();
  }

  private void resetInboxToReceived(String eventId) throws Exception {
    try (Connection admin = AuthTestSupport.admin();
        var statement =
            admin.prepareStatement(
                """
                UPDATE inbox_event
                SET status = 'RECEIVED', attempts = 0, last_error = NULL, processed_at = NULL
                WHERE event_id = ?
                """)) {
      statement.setString(1, eventId);
      assertThat(statement.executeUpdate()).isEqualTo(1);
    }
  }

  private UUID orderId(StockFixture.Shop shop, String externalOrderId) {
    return fixture.inTenant(
        shop.tenant(),
        () ->
            jdbc.queryForObject(
                "SELECT id FROM sales_order WHERE external_order_id = ?",
                UUID.class,
                externalOrderId));
  }

  private String holdReason(StockFixture.Shop shop, UUID orderId) {
    return fixture.inTenant(
        shop.tenant(),
        () ->
            jdbc.queryForObject(
                "SELECT hold_reason FROM sales_order WHERE id = ?", String.class, orderId));
  }

  private String holdNote(StockFixture.Shop shop, UUID orderId) {
    return fixture.inTenant(
        shop.tenant(),
        () ->
            jdbc.queryForObject(
                "SELECT hold_note FROM sales_order WHERE id = ?", String.class, orderId));
  }

  private long historyHoldCount(StockFixture.Shop shop, UUID orderId) {
    return fixture.inTenant(
        shop.tenant(),
        () ->
            jdbc.queryForObject(
                """
                SELECT count(*) FROM order_status_history
                WHERE order_id = ? AND dimension = 'HOLD'
                """,
                Long.class,
                orderId));
  }

  private long orderReservationCount(StockFixture.Shop shop, UUID orderId) {
    return fixture.inTenant(
        shop.tenant(),
        () ->
            jdbc.queryForObject(
                """
                SELECT count(*) FROM stock_reservation
                WHERE owner_type = 'ORDER' AND owner_ref = ?::text
                """,
                Long.class,
                orderId.toString()));
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
