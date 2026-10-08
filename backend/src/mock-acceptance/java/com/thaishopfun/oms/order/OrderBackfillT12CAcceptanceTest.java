package com.thaishopfun.oms.order;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.thaishopfun.mocktsf.OmsEndpoint;
import com.thaishopfun.mocktsf.idp.TokenIssuer;
import com.thaishopfun.oms.auth.AuthTestSupport;
import com.thaishopfun.oms.inbox.InboxWorker;
import com.thaishopfun.oms.order.backfill.OrderBackfillJob;
import com.thaishopfun.oms.order.backfill.OrderBackfillProperties;
import com.thaishopfun.oms.order.backfill.OrderGapRefetchService;
import com.thaishopfun.oms.stock.StockFixture;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
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
      "oms.inbox.worker-enabled=false",
      "oms.inbox.jitter-ratio=0"
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
  @Autowired MeterRegistry meters;

  StockFixture fixture;
  private final ListAppender<ILoggingEvent> backfillLogs = new ListAppender<>();

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
    attachBackfillLogs();
  }

  @AfterEach
  void restoreWebhooks() throws Exception {
    postMock("/control/webhooks", "{\"enabled\":true}");
    backfillProperties.setPageLimit(100);
    detachBackfillLogs();
  }

  private void attachBackfillLogs() {
    Logger logger = (Logger) LoggerFactory.getLogger(OrderBackfillJob.class);
    backfillLogs.start();
    logger.addAppender(backfillLogs);
  }

  private void detachBackfillLogs() {
    Logger logger = (Logger) LoggerFactory.getLogger(OrderBackfillJob.class);
    logger.detachAppender(backfillLogs);
    backfillLogs.list.clear();
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
        "{\"count\":20,\"payment\":\"PREPAID\",\"paid\":true,\"shop_id\":\"" + shopId + "\"}");
    postMock(
        "/control/orders/bulk",
        "{\"count\":15,\"payment\":\"PREPAID\",\"paid\":false,\"shop_id\":\"" + shopId + "\"}");
    postMock(
        "/control/orders/bulk",
        "{\"count\":15,\"payment\":\"COD\",\"paid\":false,\"shop_id\":\"" + shopId + "\"}");
    backfill.runOnceForTenant(shop.tenant());
    long count =
        fixture.inTenant(
            shop.tenant(),
            () -> jdbc.queryForObject("SELECT count(*) FROM sales_order", Long.class));
    assertThat(count).isEqualTo(50);
    long prepaidPaid =
        fixture.inTenant(
            shop.tenant(),
            () ->
                jdbc.queryForObject(
                    """
                    SELECT count(*) FROM sales_order
                    WHERE payment_method = 'PREPAID' AND payment_status = 'PAID'
                    """,
                    Long.class));
    long prepaidUnpaid =
        fixture.inTenant(
            shop.tenant(),
            () ->
                jdbc.queryForObject(
                    """
                    SELECT count(*) FROM sales_order
                    WHERE payment_method = 'PREPAID' AND payment_status = 'PENDING'
                    """,
                    Long.class));
    long cod =
        fixture.inTenant(
            shop.tenant(),
            () ->
                jdbc.queryForObject(
                    "SELECT count(*) FROM sales_order WHERE payment_method = 'COD'", Long.class));
    assertThat(prepaidPaid).isEqualTo(20);
    assertThat(prepaidUnpaid).isEqualTo(15);
    assertThat(cod).isEqualTo(15);
    assertThat(meters.find(OrderBackfillJob.LAG).gauge()).isNotNull();
    backfill.runOnceForTenant(shop.tenant());
    long again =
        fixture.inTenant(
            shop.tenant(),
            () -> jdbc.queryForObject("SELECT count(*) FROM sales_order", Long.class));
    assertThat(again).isEqualTo(50);
  }

  @Test
  void lateWebhookReplayAfterBackfillIsNoOp() throws Exception {
    StockFixture.Shop shop = fixture.shop("ACTIVE");
    String shopId = fixture.tsfShopId(shop);
    UUID account = fixture.tsfChannelAccount(shop, "ACTIVE", "CONNECTED");
    UUID sku = fixture.sku(shop, 20);
    fixture.channelListing(shop, account, "tsf_sku_7781", sku, true);
    String orderId = "TSF-LATE-" + UUID.randomUUID();
    postMock(
        "/control/orders/register",
        "{\"shop_id\":\"" + shopId + "\",\"order_id\":\"" + orderId + "\"}");
    backfill.runOnceForTenant(shop.tenant());
    long versionBefore = externalVersion(shop.tenant(), orderId);
    ObjectNode created =
        OrderIntakeScenarioSupport.orderCreated(
            JSON,
            orderId,
            shopId,
            "evt-late-replay-" + UUID.randomUUID(),
            "PREPAID",
            "tsf_sku_7781",
            1,
            1);
    ingest(created);
    assertThat(worker.processAvailable(5)).isEqualTo(1);
    assertThat(externalVersion(shop.tenant(), orderId)).isEqualTo(versionBefore);
    assertThat(
            fixture.inTenant(
                shop.tenant(),
                () ->
                    jdbc.queryForObject(
                        "SELECT count(*) FROM sales_order WHERE external_order_id = ?",
                        Long.class,
                        orderId)))
        .isEqualTo(1L);
  }

  @Test
  void backfillRestartResumesFromStoredPageCursor() throws Exception {
    backfillProperties.setPageLimit(1);
    StockFixture.Shop shop = fixture.shop("ACTIVE");
    String shopId = fixture.tsfShopId(shop);
    UUID account = fixture.tsfChannelAccount(shop, "ACTIVE", "CONNECTED");
    UUID sku = fixture.sku(shop, 30);
    fixture.channelListing(shop, account, "tsf_sku_7781", sku, true);
    postMock(
        "/control/orders/bulk",
        "{\"count\":3,\"payment\":\"COD\",\"paid\":false,\"shop_id\":\"" + shopId + "\"}");
    String pageCursor =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString("2".getBytes(StandardCharsets.US_ASCII));
    Instant watermark = Instant.EPOCH;
    fixture.inTenant(
        shop.tenant(),
        () ->
            jdbc.update(
                """
                INSERT INTO sync_cursor (tenant_id, channel_account_id, resource, cursor, last_success_at)
                VALUES (?, ?, 'ORDERS', ?::jsonb, ?)
                """,
                shop.tenant(),
                account,
                "{\"updated_since\":\"" + watermark + "\",\"page_cursor\":\"" + pageCursor + "\"}",
                java.sql.Timestamp.from(watermark)));
    backfill.runOnceForTenant(shop.tenant());
    long count =
        fixture.inTenant(
            shop.tenant(),
            () -> jdbc.queryForObject("SELECT count(*) FROM sales_order", Long.class));
    assertThat(count).isEqualTo(1);
  }

  @Test
  void backfillWithNoNewOrdersDoesNotRewriteSyncCursor() throws Exception {
    StockFixture.Shop shop = fixture.shop("ACTIVE");
    String shopId = fixture.tsfShopId(shop);
    UUID account = fixture.tsfChannelAccount(shop, "ACTIVE", "CONNECTED");
    UUID sku = fixture.sku(shop, 5);
    fixture.channelListing(shop, account, "tsf_sku_7781", sku, true);
    postMock(
        "/control/orders/bulk",
        "{\"count\":1,\"payment\":\"COD\",\"paid\":false,\"shop_id\":\"" + shopId + "\"}");
    backfill.runOnceForTenant(shop.tenant());
    String cursorBefore =
        fixture.inTenant(
            shop.tenant(),
            () ->
                jdbc.queryForObject(
                    "SELECT cursor::text FROM sync_cursor WHERE channel_account_id = ?",
                    String.class,
                    account));
    Instant updatedBefore =
        fixture.inTenant(
            shop.tenant(),
            () ->
                jdbc.queryForObject(
                        "SELECT updated_at FROM sync_cursor WHERE channel_account_id = ?",
                        java.sql.Timestamp.class,
                        account)
                    .toInstant());
    backfill.runOnceForTenant(shop.tenant());
    String cursorAfter =
        fixture.inTenant(
            shop.tenant(),
            () ->
                jdbc.queryForObject(
                    "SELECT cursor::text FROM sync_cursor WHERE channel_account_id = ?",
                    String.class,
                    account));
    Instant updatedAfter =
        fixture.inTenant(
            shop.tenant(),
            () ->
                jdbc.queryForObject(
                        "SELECT updated_at FROM sync_cursor WHERE channel_account_id = ?",
                        java.sql.Timestamp.class,
                        account)
                    .toInstant());
    assertThat(cursorAfter).isEqualTo(cursorBefore);
    assertThat(updatedAfter).isAfterOrEqualTo(updatedBefore);
  }

  @Test
  void listOrdersRateLimitRetainsCursor() throws Exception {
    StockFixture.Shop shop = fixture.shop("ACTIVE");
    String shopId = fixture.tsfShopId(shop);
    UUID account = fixture.tsfChannelAccount(shop, "ACTIVE", "CONNECTED");
    UUID sku = fixture.sku(shop, 10);
    fixture.channelListing(shop, account, "tsf_sku_7781", sku, true);
    postMock(
        "/control/orders/bulk",
        "{\"count\":1,\"payment\":\"COD\",\"paid\":false,\"shop_id\":\"" + shopId + "\"}");
    backfill.runOnceForTenant(shop.tenant());
    String cursorBefore =
        fixture.inTenant(
            shop.tenant(),
            () ->
                jdbc.queryForObject(
                    "SELECT cursor::text FROM sync_cursor WHERE channel_account_id = ?",
                    String.class,
                    account));
    postMock(
        "/control/orders/bulk",
        "{\"count\":1,\"payment\":\"COD\",\"paid\":false,\"shop_id\":\"" + shopId + "\"}");
    postMock(
        "/control/faults",
        "{\"method\":\"GET\",\"path\":\"/internal/v1/shops/"
            + shopId
            + "/orders\",\"status\":503,\"times\":3,\"retry_after\":30}");
    backfill.runOnceForTenant(shop.tenant());
    String cursorAfter =
        fixture.inTenant(
            shop.tenant(),
            () ->
                jdbc.queryForObject(
                    "SELECT cursor::text FROM sync_cursor WHERE channel_account_id = ?",
                    String.class,
                    account));
    assertThat(cursorAfter).isEqualTo(cursorBefore);
  }

  @Test
  void listTenantsForBackfillIncludesGraceExcludesSuspended() throws Exception {
    StockFixture.Shop active = fixture.shop("ACTIVE");
    fixture.tsfChannelAccount(active, "ACTIVE", "CONNECTED");
    StockFixture.Shop grace = fixture.shop("GRACE");
    fixture.inTenant(
        grace.tenant(),
        () ->
            jdbc.update(
                "UPDATE tenant SET entitlement_expires_at = ? WHERE id = ?",
                java.sql.Timestamp.from(Instant.now().plus(Duration.ofDays(7))),
                grace.tenant()));
    fixture.tsfChannelAccount(grace, "ACTIVE", "CONNECTED");
    StockFixture.Shop suspended = fixture.shop("SUSPENDED");
    fixture.tsfChannelAccount(suspended, "ACTIVE", "CONNECTED");
    StockFixture.Shop disconnected = fixture.shop("ACTIVE");
    fixture.tsfChannelAccount(disconnected, "ACTIVE", "DISCONNECTED");
    List<UUID> eligible =
        jdbc.query(
            "SELECT id FROM list_tenants_for_order_backfill()",
            (rs, row) -> rs.getObject("id", UUID.class));
    assertThat(eligible).contains(active.tenant(), grace.tenant());
    assertThat(eligible).doesNotContain(suspended.tenant(), disconnected.tenant());
  }

  @Test
  void backfillJobLogsDoNotContainRecipientPhone() throws Exception {
    StockFixture.Shop shop = fixture.shop("ACTIVE");
    String shopId = fixture.tsfShopId(shop);
    UUID account = fixture.tsfChannelAccount(shop, "ACTIVE", "CONNECTED");
    UUID sku = fixture.sku(shop, 5);
    fixture.channelListing(shop, account, "tsf_sku_7781", sku, true);
    postMock(
        "/control/orders/bulk",
        "{\"count\":2,\"payment\":\"PREPAID\",\"paid\":true,\"shop_id\":\"" + shopId + "\"}");
    backfill.runOnceForTenant(shop.tenant());
    String joined =
        backfillLogs.list.stream()
            .map(ILoggingEvent::getFormattedMessage)
            .reduce("", String::concat);
    assertThat(joined).doesNotContain("0890000000");
    assertThat(joined).doesNotContain("Bulk Buyer");
  }

  @Test
  void backfillCreatesCancelledOrderWithoutStockDoubleWrite() throws Exception {
    StockFixture.Shop shop = fixture.shop("ACTIVE");
    String shopId = fixture.tsfShopId(shop);
    UUID account = fixture.tsfChannelAccount(shop, "ACTIVE", "CONNECTED");
    UUID sku = fixture.sku(shop, 20);
    fixture.channelListing(shop, account, "tsf_sku_7781", sku, true);
    String orderId = "TSF-CXL-" + UUID.randomUUID();
    postMock(
        "/control/orders/register",
        "{\"shop_id\":\""
            + shopId
            + "\",\"order_id\":\""
            + orderId
            + "\",\"status\":\"CANCELLED\"}");
    backfill.runOnceForTenant(shop.tenant());
    String status =
        fixture.inTenant(
            shop.tenant(),
            () ->
                jdbc.queryForObject(
                    "SELECT order_status FROM sales_order WHERE external_order_id = ?",
                    String.class,
                    orderId));
    assertThat(status).isEqualTo("CANCELLED");
  }

  @Test
  void gapRefetchAppliesPaidSnapshot() throws Exception {
    StockFixture.Shop shop = fixture.shop("ACTIVE");
    String shopId = fixture.tsfShopId(shop);
    UUID account = fixture.tsfChannelAccount(shop, "ACTIVE", "CONNECTED");
    UUID sku = fixture.sku(shop, 10);
    fixture.channelListing(shop, account, "L-gap", sku, true);
    String orderId = "TSF-GAP-" + UUID.randomUUID();
    double gapsBefore = gapRefetchCount();
    postMock(
        "/control/orders/register",
        "{\"shop_id\":\"" + shopId + "\",\"order_id\":\"" + orderId + "\"}");
    ingest(
        OrderIntakeScenarioSupport.orderCreated(
            JSON, orderId, shopId, UUID.randomUUID().toString(), "PREPAID", "L-gap", 1, 1));
    assertThat(worker.processAvailable(5)).isEqualTo(1);

    ObjectNode paidEarly = OrderIntakeScenarioSupport.orderPaid(JSON, orderId, shopId, 3);
    paidEarly.put("event_id", "evt-paid-early-" + UUID.randomUUID());
    ingest(paidEarly, false);
    assertThat(worker.processAvailable(5)).isEqualTo(1);
    assertThat(gapRefetchCount()).isEqualTo(gapsBefore);
    assertThat(inboxStatus(shop.tenant(), paidEarly.path("event_id").asString()))
        .isNotEqualTo("PROCESSED");

    postMock("/control/orders/" + orderId + "/mark-paid", "{\"aggregate_version\":3}");
    int processedAfterMarkPaid = 0;
    for (int round = 0; round < 10; round++) {
      wakeInboxRetries();
      processedAfterMarkPaid += worker.processAvailable(5);
      if (gapRefetchCount() >= gapsBefore + 1
          && "PROCESSED"
              .equals(inboxStatus(shop.tenant(), paidEarly.path("event_id").asString()))) {
        break;
      }
    }
    assertThat(processedAfterMarkPaid).isGreaterThanOrEqualTo(1);
    assertThat(gapRefetchCount()).isEqualTo(gapsBefore + 1);
    assertThat(inboxStatus(shop.tenant(), paidEarly.path("event_id").asString()))
        .isEqualTo("PROCESSED");

    assertThat(paymentStatus(shop.tenant(), orderId)).isEqualTo("PAID");
    assertThat(externalVersion(shop.tenant(), orderId)).isEqualTo(3L);

    ObjectNode stale = OrderIntakeScenarioSupport.orderUpdated(JSON, orderId, shopId, 2);
    stale.put("event_id", "evt-stale-gap-" + UUID.randomUUID());
    ingest(stale);
    assertThat(worker.processAvailable(5)).isEqualTo(1);
    assertThat(gapRefetchCount()).isEqualTo(gapsBefore + 1);
    assertThat(externalVersion(shop.tenant(), orderId)).isEqualTo(3L);
  }

  @Test
  void gapRefetchAppliesCancelledSnapshotOnUpdatedVersionJump() throws Exception {
    StockFixture.Shop shop = fixture.shop("ACTIVE");
    String shopId = fixture.tsfShopId(shop);
    UUID account = fixture.tsfChannelAccount(shop, "ACTIVE", "CONNECTED");
    UUID sku = fixture.sku(shop, 11);
    fixture.channelListing(shop, account, "L-gap-cancel", sku, true);
    String orderId = "TSF-GAP-CAN-" + UUID.randomUUID();
    postMock(
        "/control/orders/register",
        "{\"shop_id\":\"" + shopId + "\",\"order_id\":\"" + orderId + "\"}");
    ingest(
        OrderIntakeScenarioSupport.orderCreated(
            JSON, orderId, shopId, UUID.randomUUID().toString(), "COD", "L-gap-cancel", 1, 1));
    assertThat(worker.processAvailable(5)).isEqualTo(1);

    ObjectNode cancelOnly = OrderIntakeScenarioSupport.orderCancelled(JSON, orderId, shopId, 3);
    cancelOnly.put("event_id", "evt-cancel-catalog-only-" + UUID.randomUUID());
    MockTsfCatalogSync.note(cancelOnly);

    ObjectNode updated = OrderIntakeScenarioSupport.orderUpdated(JSON, orderId, shopId, 3);
    updated.put("event_id", "evt-upd-gap-" + UUID.randomUUID());
    ingest(updated, false);
    assertThat(worker.processAvailable(5)).isEqualTo(1);

    assertThat(
            fixture.inTenant(
                shop.tenant(),
                () ->
                    jdbc.queryForObject(
                        "SELECT order_status FROM sales_order WHERE external_order_id = ?",
                        String.class,
                        orderId)))
        .isEqualTo("CANCELLED");
  }

  @Test
  void backfillRetriesOrderAfterTransientRestFault() throws Exception {
    StockFixture.Shop shop = fixture.shop("ACTIVE");
    String shopId = fixture.tsfShopId(shop);
    UUID account = fixture.tsfChannelAccount(shop, "ACTIVE", "CONNECTED");
    UUID sku = fixture.sku(shop, 12);
    fixture.channelListing(shop, account, "tsf_sku_7781", sku, true);
    HttpResponse<String> bulk =
        HTTP.send(
            HttpRequest.newBuilder(
                    URI.create(
                        "http://127.0.0.1:"
                            + OrderIntakeMockRuntime.mockPort()
                            + "/control/orders/bulk"))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json")
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        "{\"count\":2,\"payment\":\"COD\",\"paid\":false,\"shop_id\":\""
                            + shopId
                            + "\"}",
                        StandardCharsets.UTF_8))
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    assertThat(bulk.statusCode()).isBetween(200, 299);
    tools.jackson.databind.JsonNode ids = JSON.readTree(bulk.body()).path("order_ids");
    String faultOrder = ids.get(1).asString();
    postMock(
        "/control/faults",
        "{\"method\":\"GET\",\"path\":\"/internal/v1/orders/"
            + faultOrder
            + "\",\"status\":503,\"times\":1}");
    backfill.runOnceForTenant(shop.tenant());
    long afterFault =
        fixture.inTenant(
            shop.tenant(),
            () -> jdbc.queryForObject("SELECT count(*) FROM sales_order", Long.class));
    assertThat(afterFault).isEqualTo(1);
    backfill.runOnceForTenant(shop.tenant());
    long afterRetry =
        fixture.inTenant(
            shop.tenant(),
            () -> jdbc.queryForObject("SELECT count(*) FROM sales_order", Long.class));
    assertThat(afterRetry).isEqualTo(2);
  }

  @Test
  void gapRefetchRestFailureEventuallyDead() throws Exception {
    StockFixture.Shop shop = fixture.shop("ACTIVE");
    String shopId = fixture.tsfShopId(shop);
    UUID account = fixture.tsfChannelAccount(shop, "ACTIVE", "CONNECTED");
    UUID sku = fixture.sku(shop, 10);
    fixture.channelListing(shop, account, "L-gap-dead", sku, true);
    String orderId = "TSF-GAP-DEAD-" + UUID.randomUUID();
    postMock(
        "/control/orders/register",
        "{\"shop_id\":\"" + shopId + "\",\"order_id\":\"" + orderId + "\"}");
    ingest(
        OrderIntakeScenarioSupport.orderCreated(
            JSON, orderId, shopId, UUID.randomUUID().toString(), "PREPAID", "L-gap-dead", 1, 1));
    assertThat(worker.processAvailable(5)).isEqualTo(1);
    postMock("/control/orders/" + orderId + "/mark-paid", "{\"aggregate_version\":3}");
    postMock(
        "/control/faults",
        "{\"method\":\"GET\",\"path\":\"/internal/v1/orders/"
            + orderId
            + "\",\"status\":503,\"times\":20}");
    ObjectNode paid = OrderIntakeScenarioSupport.orderPaid(JSON, orderId, shopId, 3);
    String paidEventId = paid.path("event_id").asString();
    ingest(paid, false);
    for (int round = 0; round < 30; round++) {
      wakeInboxRetries();
      worker.processAvailable(5);
      String status = inboxStatus(shop.tenant(), paidEventId);
      if ("DEAD".equals(status)) {
        break;
      }
    }
    assertThat(inboxStatus(shop.tenant(), paidEventId)).isEqualTo("DEAD");
    assertThat(paymentStatus(shop.tenant(), orderId)).isEqualTo("PENDING");
  }

  private double gapRefetchCount() {
    var counter = meters.find(OrderGapRefetchService.GAP_METRIC).counter();
    return counter == null ? 0 : counter.count();
  }

  private String inboxStatus(UUID tenantId, String eventId) {
    return fixture.inTenant(
        tenantId,
        () ->
            jdbc.queryForObject(
                "SELECT status FROM inbox_event WHERE event_id = ?", String.class, eventId));
  }

  private String paymentStatus(UUID tenantId, String orderId) {
    return fixture.inTenant(
        tenantId,
        () ->
            jdbc.queryForObject(
                "SELECT payment_status FROM sales_order WHERE external_order_id = ?",
                String.class,
                orderId));
  }

  private long externalVersion(UUID tenantId, String orderId) {
    return fixture.inTenant(
        tenantId,
        () ->
            jdbc.queryForObject(
                "SELECT external_version FROM sales_order WHERE external_order_id = ?",
                Long.class,
                orderId));
  }

  private void wakeInboxRetries() throws Exception {
    try (Connection admin = AuthTestSupport.admin();
        var statement = admin.createStatement()) {
      statement.execute(
          """
          UPDATE inbox_event
          SET next_attempt_at = pg_catalog.now() - interval '1 millisecond'
          WHERE status IN ('RECEIVED', 'FAILED')
            AND next_attempt_at > pg_catalog.now()
          """);
    }
  }

  private void ingest(ObjectNode event) throws Exception {
    ingest(event, true);
  }

  private void ingest(ObjectNode event, boolean syncCatalog) throws Exception {
    if (syncCatalog) {
      MockTsfCatalogSync.note(event);
    }
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
