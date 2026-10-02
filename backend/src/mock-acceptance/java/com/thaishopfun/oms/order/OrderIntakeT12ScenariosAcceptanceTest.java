package com.thaishopfun.oms.order;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.thaishopfun.mocktsf.MockTsfApplication;
import com.thaishopfun.mocktsf.OmsEndpoint;
import com.thaishopfun.mocktsf.contract.ContractValidator;
import com.thaishopfun.mocktsf.idp.TokenIssuer;
import com.thaishopfun.oms.auth.AuthTestSupport;
import com.thaishopfun.oms.auth.UuidV7;
import com.thaishopfun.oms.inbox.InboxWorker;
import com.thaishopfun.oms.order.intake.OrderIntakeSupport;
import com.thaishopfun.oms.outbox.OutboxPublisher;
import com.thaishopfun.oms.stock.OrderIntakeFaultTestConfig;
import com.thaishopfun.oms.stock.StockFixture;
import com.thaishopfun.oms.tenant.TenantContext;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.io.InputStream;
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
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.Timeout.ThreadMode;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * T12 mock acceptance scenarios (hand-off, expiry, atomicity, reconciliation, tenant isolation).
 */
@ActiveProfiles("test")
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"spring.main.allow-bean-definition-overriding=true"})
@Import(OrderIntakeT12ScenariosAcceptanceTest.IntakeTestConfig.class)
@Timeout(value = 5, unit = TimeUnit.MINUTES, threadMode = ThreadMode.SEPARATE_THREAD)
class OrderIntakeT12ScenariosAcceptanceTest {

  private static final String ISSUER = OrderIntakeMockRuntime.issuer();
  private static final String INBOX_SECRET = "dev-inbox-hmac-secret";
  private static final String OUTBOX_SECRET = "dev-outbox-webhook-secret-local-only";
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final HttpClient HTTP =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
  private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(20);
  private static final ContractValidator CONTRACT = ContractValidator.classpath();
  private static final AtomicReference<String> MAX_DEFER = new AtomicReference<>("24h");

  private static ConfigurableApplicationContext mock() {
    return OrderIntakeMockRuntime.mock();
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    OrderIntakeMockRuntime.startMock();
    AuthTestSupport.register(registry);
    int mockPort = OrderIntakeMockRuntime.mockPort();
    registry.add("oms.security.issuer", () -> ISSUER);
    registry.add(
        "oms.security.jwks-uri",
        () -> "http://127.0.0.1:" + mockPort + "/tsf-idp/.well-known/jwks.json");
    registry.add("oms.security.internal-client-ids", () -> "tsf,tsf-checkout");
    registry.add("oms.inbox.hmac-secrets", () -> INBOX_SECRET);
    registry.add("oms.inbox.jitter-ratio", () -> "0");
    registry.add("oms.inbox.max-defer", () -> MAX_DEFER.get());
    registry.add("oms.outbox.publisher-enabled", () -> "false");
    registry.add("oms.outbox.jitter-ratio", () -> "0");
    registry.add(
        "oms.outbox.destination-url",
        () -> "http://127.0.0.1:" + mockPort + "/internal/v1/oms-events");
    registry.add("oms.outbox.webhook-secret", () -> OUTBOX_SECRET);
  }

  @LocalServerPort private int port;

  @Autowired InboxWorker worker;
  @Autowired OutboxPublisher publisher;
  @Autowired JdbcTemplate jdbc;
  @Autowired PlatformTransactionManager transactions;
  @Autowired MeterRegistry meters;
  @Autowired OrderRecipientRepository recipients;

  StockFixture fixture;
  private final ListAppender<ILoggingEvent> intakeLogs = new ListAppender<>();

  @BeforeEach
  void setup() throws Exception {
    MAX_DEFER.set("24h");
    mock().getBean(OmsEndpoint.class).setBaseUrl("http://127.0.0.1:" + port);
    fixture = new StockFixture(jdbc, transactions);
    IntakeTestConfig.failAfterOutbox.set(false);
    IntakeTestConfig.afterOutboxCalls.set(0);
    attachIntakeLogs();
    try (Connection admin = AuthTestSupport.admin();
        var statement = admin.createStatement()) {
      statement.execute("SET lock_timeout = '10s'");
      statement.execute("SET statement_timeout = '30s'");
      statement.execute("SET session_replication_role = replica");
      statement.execute(
          "TRUNCATE TABLE outbox_event, inbox_event, order_status_history, order_line, "
              + "order_recipient, sales_order, stock_reservation, inventory_ledger, inventory, "
              + "idempotency_key, reconciliation_issue, shadow_diff CASCADE");
      statement.execute("SET session_replication_role = DEFAULT");
    }
  }

  @AfterAll
  static void stopMockTsf() {
    OrderIntakeMockRuntime.stopMock();
  }

  @AfterEach
  void clearTenant() {
    detachIntakeLogs();
    TenantContext.clear();
  }

  @Test
  void createdAtomicityRollsBackAllSideEffectsWhenHookFails() throws Exception {
    StockFixture.Shop shop = fixture.shop("ACTIVE");
    String shopId = fixture.tsfShopId(shop);
    UUID account = fixture.tsfChannelAccount(shop, "ACTIVE", "CONNECTED");
    UUID sku = fixture.sku(shop, 4);
    fixture.channelListing(shop, account, "L-atom-cr", sku, true);

    JsonNode checkout = checkoutViaControl("chk-atom-cr", shopId, "L-atom-cr", 2);
    String reservationId = checkout.path("reservation_id").asString();
    String externalOrderId = "TSF-AT-CR-" + UUID.randomUUID();
    ObjectNode created =
        orderCreated(externalOrderId, shopId, reservationId, "COD", "L-atom-cr", 2, 1);
    ingest(created);
    String eventId = created.path("event_id").asString();

    IntakeTestConfig.failAfterOutbox.set(true);
    assertThat(worker.processAvailable(10)).isEqualTo(1);
    assertThat(countSalesOrders(externalOrderId)).isZero();
    assertThat(tableCount("order_line")).isZero();
    assertThat(tableCount("order_recipient")).isZero();
    assertThat(tableCount("order_status_history")).isZero();
    assertThat(outboxCount()).isZero();
    assertThat(
            fixture.inTenant(
                shop.tenant(),
                () ->
                    jdbc.queryForObject(
                        "SELECT count(*) FROM stock_reservation WHERE owner_type = 'CHECKOUT' AND status = 'ACTIVE'",
                        Long.class)))
        .isEqualTo(1);
    assertThat(
            fixture.inTenant(
                shop.tenant(),
                () ->
                    jdbc.queryForObject(
                        "SELECT count(*) FROM stock_reservation WHERE owner_type = 'ORDER'",
                        Long.class)))
        .isZero();
    assertThat(
            fixture.inTenant(
                shop.tenant(),
                () ->
                    jdbc.queryForObject(
                        "SELECT count(*) FROM idempotency_key WHERE scope = 'stock.adopt'",
                        Long.class)))
        .isZero();
    assertThat(text("SELECT status FROM inbox_event WHERE event_id = ?", eventId))
        .isEqualTo("FAILED");

    IntakeTestConfig.failAfterOutbox.set(false);
    rewind(eventId);
    assertThat(worker.processAvailable(10)).isEqualTo(1);
    assertThat(countSalesOrders(externalOrderId)).isEqualTo(1);
    assertThat(outboxCount()).isEqualTo(1);
  }

  @Test
  void paidAtomicityRollsBackPaymentAndOutboxWhenHookFails() throws Exception {
    StockFixture.Shop shop = fixture.shop("ACTIVE");
    String shopId = fixture.tsfShopId(shop);
    UUID account = fixture.tsfChannelAccount(shop, "ACTIVE", "CONNECTED");
    UUID sku = fixture.sku(shop, 5);
    fixture.channelListing(shop, account, "L-atom-pd", sku, true);

    JsonNode checkout = checkoutViaControl("chk-atom-pd", shopId, "L-atom-pd", 2);
    String reservationId = checkout.path("reservation_id").asString();
    String externalOrderId = "TSF-AT-PD-" + UUID.randomUUID();
    ingest(orderCreated(externalOrderId, shopId, reservationId, "PREPAID", "L-atom-pd", 2, 1));
    assertThat(worker.processAvailable(10)).isEqualTo(1);

    ObjectNode paid = orderPaid(externalOrderId, shopId, 2);
    ingest(paid);
    String paidEventId = paid.path("event_id").asString();

    IntakeTestConfig.failAfterOutbox.set(true);
    assertThat(worker.processAvailable(10)).isEqualTo(1);
    assertThat(
            fixture.inTenant(
                shop.tenant(),
                () ->
                    jdbc.queryForObject(
                        "SELECT payment_status FROM sales_order WHERE external_order_id = ?",
                        String.class,
                        externalOrderId)))
        .isEqualTo("PENDING");
    assertThat(outboxCount()).isZero();
    assertThat(text("SELECT status FROM inbox_event WHERE event_id = ?", paidEventId))
        .isEqualTo("FAILED");

    IntakeTestConfig.failAfterOutbox.set(false);
    rewind(paidEventId);
    assertThat(worker.processAvailable(10)).isEqualTo(1);
    assertThat(
            fixture.inTenant(
                shop.tenant(),
                () ->
                    jdbc.queryForObject(
                        "SELECT payment_status FROM sales_order WHERE external_order_id = ?",
                        String.class,
                        externalOrderId)))
        .isEqualTo("PAID");
  }

  @Test
  void cancelledAtomicityRollsBackCancelWhenHookFails() throws Exception {
    StockFixture.Shop shop = fixture.shop("ACTIVE");
    String shopId = fixture.tsfShopId(shop);
    UUID account = fixture.tsfChannelAccount(shop, "ACTIVE", "CONNECTED");
    UUID sku = fixture.sku(shop, 5);
    fixture.channelListing(shop, account, "L-atom-cn", sku, true);

    JsonNode checkout = checkoutViaControl("chk-atom-cn", shopId, "L-atom-cn", 2);
    String reservationId = checkout.path("reservation_id").asString();
    String externalOrderId = "TSF-AT-CN-" + UUID.randomUUID();
    ingest(orderCreated(externalOrderId, shopId, reservationId, "COD", "L-atom-cn", 2, 1));
    assertThat(worker.processAvailable(10)).isEqualTo(1);
    assertThat(fixture.reserved(shop, sku)).isEqualTo(2);

    ObjectNode cancelled = orderCancelled(externalOrderId, shopId, 2);
    ingest(cancelled);
    String cancelEventId = cancelled.path("event_id").asString();

    IntakeTestConfig.failAfterOutbox.set(true);
    assertThat(worker.processAvailable(10)).isEqualTo(1);
    assertThat(
            fixture.inTenant(
                shop.tenant(),
                () ->
                    jdbc.queryForObject(
                        "SELECT order_status FROM sales_order WHERE external_order_id = ?",
                        String.class,
                        externalOrderId)))
        .isEqualTo("ACTIVE");
    assertThat(fixture.reserved(shop, sku)).isEqualTo(2);
    assertThat(text("SELECT status FROM inbox_event WHERE event_id = ?", cancelEventId))
        .isEqualTo("FAILED");

    IntakeTestConfig.failAfterOutbox.set(false);
    rewind(cancelEventId);
    assertThat(worker.processAvailable(10)).isEqualTo(1);
    assertThat(
            fixture.inTenant(
                shop.tenant(),
                () ->
                    jdbc.queryForObject(
                        "SELECT order_status FROM sales_order WHERE external_order_id = ?",
                        String.class,
                        externalOrderId)))
        .isEqualTo("CANCELLED");
    assertThat(fixture.reserved(shop, sku)).isZero();
  }

  @Test
  void checkoutHandOffSetsCodReservationWithoutExpiry() throws Exception {
    StockFixture.Shop shop = fixture.shop("ACTIVE");
    String shopId = fixture.tsfShopId(shop);
    UUID account = fixture.tsfChannelAccount(shop, "ACTIVE", "CONNECTED");
    UUID sku = fixture.sku(shop, 6);
    fixture.channelListing(shop, account, "L-ho-cod", sku, true);

    JsonNode checkout = checkoutViaControl("chk-ho-cod", shopId, "L-ho-cod", 2);
    UUID groupId = UUID.fromString(checkout.path("reservation_id").asString());
    String externalOrderId = "TSF-HO-COD-" + UUID.randomUUID();
    ingest(orderCreated(externalOrderId, shopId, groupId.toString(), "COD", "L-ho-cod", 2, 1));
    assertThat(worker.processAvailable(10)).isEqualTo(1);

    assertThat(
            fixture.inTenant(
                shop.tenant(),
                () ->
                    jdbc.queryForObject(
                        """
                        SELECT count(*) FROM stock_reservation
                        WHERE reservation_group_id = ? AND owner_type = 'ORDER'
                          AND status = 'ACTIVE' AND expires_at IS NULL
                        """,
                        Long.class,
                        groupId)))
        .isEqualTo(1);
  }

  @Test
  void checkoutHandOffSetsPrepaidReservationExpiryFromPaymentWindow() throws Exception {
    StockFixture.Shop shop = fixture.shop("ACTIVE");
    String shopId = fixture.tsfShopId(shop);
    UUID account = fixture.tsfChannelAccount(shop, "ACTIVE", "CONNECTED");
    UUID sku = fixture.sku(shop, 6);
    fixture.channelListing(shop, account, "L-ho-pp", sku, true);

    JsonNode checkout = checkoutViaControl("chk-ho-pp", shopId, "L-ho-pp", 1);
    UUID groupId = UUID.fromString(checkout.path("reservation_id").asString());
    String externalOrderId = "TSF-HO-PP-" + UUID.randomUUID();
    Instant paymentExpires =
        Instant.now().plus(30, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.SECONDS);
    ObjectNode created =
        orderCreated(externalOrderId, shopId, groupId.toString(), "PREPAID", "L-ho-pp", 1, 1);
    ((ObjectNode) created.path("data")).put("payment_expires_at", paymentExpires.toString());
    ingest(created);
    assertThat(worker.processAvailable(10)).isEqualTo(1);

    Instant expectedHold = paymentExpires.plus(10, ChronoUnit.MINUTES);
    OffsetDateTime expires =
        fixture.inTenant(
            shop.tenant(),
            () ->
                jdbc.queryForObject(
                    """
                    SELECT expires_at FROM stock_reservation
                    WHERE reservation_group_id = ? AND owner_type = 'ORDER' AND status = 'ACTIVE'
                    """,
                    OffsetDateTime.class,
                    groupId));
    assertThat(expires.toInstant()).isEqualTo(expectedHold);
  }

  @Test
  void unknownReservationIdFreshReservesOnCreated() throws Exception {
    StockFixture.Shop shop = fixture.shop("ACTIVE");
    String shopId = fixture.tsfShopId(shop);
    UUID account = fixture.tsfChannelAccount(shop, "ACTIVE", "CONNECTED");
    UUID sku = fixture.sku(shop, 4);
    fixture.channelListing(shop, account, "L-fresh", sku, true);

    String freshGroup = UuidV7.generate().toString();
    String externalOrderId = "TSF-FRESH-" + UUID.randomUUID();
    ingest(orderCreated(externalOrderId, shopId, freshGroup, "COD", "L-fresh", 2, 1));
    assertThat(worker.processAvailable(10)).isEqualTo(1);
    assertThat(fixture.reserved(shop, sku)).isEqualTo(2);
    assertThat(
            fixture.inTenant(
                shop.tenant(),
                () ->
                    jdbc.queryForObject(
                        """
                        SELECT count(*) FROM stock_reservation
                        WHERE reservation_group_id = ?::uuid AND owner_type = 'ORDER' AND status = 'ACTIVE'
                        """,
                        Long.class,
                        freshGroup)))
        .isEqualTo(1);
    fixture.assertInvariants(shop);
  }

  @Test
  void expiredCheckoutWithShortStockHoldsOutOfStock() throws Exception {
    StockFixture.Shop shop = fixture.shop("ACTIVE");
    String shopId = fixture.tsfShopId(shop);
    UUID account = fixture.tsfChannelAccount(shop, "ACTIVE", "CONNECTED");
    UUID sku = fixture.sku(shop, 2);
    fixture.channelListing(shop, account, "L-exp-short", sku, true);

    JsonNode checkout = checkoutViaControl("chk-exp-s", shopId, "L-exp-short", 2);
    UUID groupId = UUID.fromString(checkout.path("reservation_id").asString());
    backdateCheckoutExpiry(shop, groupId);

    String externalOrderId = "TSF-EXP-S-" + UUID.randomUUID();
    ObjectNode created =
        orderCreated(externalOrderId, shopId, groupId.toString(), "COD", "L-exp-short", 3, 1);
    deliverAfterReservationExpiry(
        Instant.now().minus(5, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.SECONDS), created);
    assertThat(worker.processAvailable(10)).isEqualTo(1);

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
                        "SELECT count(*) FROM stock_reservation WHERE owner_type = 'ORDER' AND status = 'ACTIVE'",
                        Long.class)))
        .isZero();
  }

  @Test
  void expiredCheckoutWithEnoughStockReReserves() throws Exception {
    StockFixture.Shop shop = fixture.shop("ACTIVE");
    String shopId = fixture.tsfShopId(shop);
    UUID account = fixture.tsfChannelAccount(shop, "ACTIVE", "CONNECTED");
    UUID sku = fixture.sku(shop, 5);
    fixture.channelListing(shop, account, "L-exp-ok", sku, true);

    JsonNode checkout = checkoutViaControl("chk-exp-ok", shopId, "L-exp-ok", 2);
    UUID groupId = UUID.fromString(checkout.path("reservation_id").asString());
    backdateCheckoutExpiry(shop, groupId);

    String externalOrderId = "TSF-EXP-OK-" + UUID.randomUUID();
    ObjectNode created =
        orderCreated(externalOrderId, shopId, groupId.toString(), "COD", "L-exp-ok", 2, 1);
    deliverAfterReservationExpiry(
        Instant.now().minus(2, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.SECONDS), created);
    assertThat(worker.processAvailable(10)).isEqualTo(1);

    assertThat(
            fixture.inTenant(
                shop.tenant(),
                () ->
                    jdbc.queryForObject(
                        "SELECT hold_reason FROM sales_order WHERE external_order_id = ?",
                        String.class,
                        externalOrderId)))
        .isEqualTo("NONE");
    assertThat(fixture.reserved(shop, sku)).isEqualTo(2);
    assertThat(
            fixture.inTenant(
                shop.tenant(),
                () ->
                    jdbc.queryForObject(
                        "SELECT fulfillment_status FROM sales_order WHERE external_order_id = ?",
                        String.class,
                        externalOrderId)))
        .isEqualTo("READY_TO_PICK");
  }

  @Test
  void cancelBeforeCreatedDefersUntilCreatedArrives() throws Exception {
    StockFixture.Shop shop = fixture.shop("ACTIVE");
    String shopId = fixture.tsfShopId(shop);
    UUID account = fixture.tsfChannelAccount(shop, "ACTIVE", "CONNECTED");
    UUID sku = fixture.sku(shop, 3);
    fixture.channelListing(shop, account, "L-cb-cr", sku, true);

    String externalOrderId = "TSF-CB-CR-" + UUID.randomUUID();
    ObjectNode cancelled = orderCancelled(externalOrderId, shopId, 1);
    ingest(cancelled);
    String cancelEventId = cancelled.path("event_id").asString();
    assertThat(worker.processAvailable(10)).isEqualTo(1);
    assertThat(text("SELECT status FROM inbox_event WHERE event_id = ?", cancelEventId))
        .isEqualTo("RECEIVED");
    assertThat(countSalesOrders(externalOrderId)).isZero();

    JsonNode checkout = checkoutViaControl("chk-cb-cr", shopId, "L-cb-cr", 1);
    ingest(
        orderCreated(
            externalOrderId,
            shopId,
            checkout.path("reservation_id").asString(),
            "COD",
            "L-cb-cr",
            1,
            1));
    assertThat(worker.processAvailable(10)).isEqualTo(1);
    assertThat(countSalesOrders(externalOrderId)).isEqualTo(1);

    rewind(cancelEventId);
    assertThat(worker.processAvailable(10)).isEqualTo(1);
    assertThat(
            fixture.inTenant(
                shop.tenant(),
                () ->
                    jdbc.queryForObject(
                        "SELECT order_status FROM sales_order WHERE external_order_id = ?",
                        String.class,
                        externalOrderId)))
        .isEqualTo("CANCELLED");
    assertThat(fixture.reserved(shop, sku)).isZero();
    assertThat(
            fixture.inTenant(
                shop.tenant(),
                () ->
                    jdbc.queryForObject(
                        """
                        SELECT count(*) FROM stock_reservation
                        WHERE owner_type = 'CHECKOUT' AND status = 'ACTIVE'
                        """,
                        Long.class)))
        .isZero();
  }

  @Test
  void deferCapCreatesReconciliationWhenPaidNeverGetsOrder() throws Exception {
    StockFixture.Shop shop = fixture.shop("ACTIVE");
    String shopId = fixture.tsfShopId(shop);
    UUID account = fixture.tsfChannelAccount(shop, "ACTIVE", "CONNECTED");
    UUID sku = fixture.sku(shop, 2);
    fixture.channelListing(shop, account, "L-def-cap", sku, true);

    String externalOrderId = "TSF-DEF-" + UUID.randomUUID();
    ObjectNode paid = orderPaid(externalOrderId, shopId, 1);
    ingest(paid);
    String paidEventId = paid.path("event_id").asString();
    assertThat(worker.processAvailable(10)).isEqualTo(1);

    backdateReceivedAt(paidEventId, Instant.now().minus(25, ChronoUnit.HOURS));
    rewind(paidEventId);
    assertThat(worker.processAvailable(10)).isEqualTo(1);
    assertThat(text("SELECT status FROM inbox_event WHERE event_id = ?", paidEventId))
        .isEqualTo("FAILED");
    assertThat(
            fixture.inTenant(
                shop.tenant(),
                () ->
                    jdbc.queryForObject(
                        """
                        SELECT count(*) FROM reconciliation_issue
                        WHERE rule = 'ORDER_EVENT_WITHOUT_ORDER' AND status = 'OPEN'
                        """,
                        Long.class)))
        .isEqualTo(1);
  }

  @Test
  void shuffledPaidThenCreatedReachesReadyToPickWithClearedExpiry() throws Exception {
    StockFixture.Shop shop = fixture.shop("ACTIVE");
    String shopId = fixture.tsfShopId(shop);
    UUID account = fixture.tsfChannelAccount(shop, "ACTIVE", "CONNECTED");
    UUID sku = fixture.sku(shop, 4);
    fixture.channelListing(shop, account, "L-shuf", sku, true);

    JsonNode checkout = checkoutViaControl("chk-shuf", shopId, "L-shuf", 2);
    UUID groupId = UUID.fromString(checkout.path("reservation_id").asString());
    String externalOrderId = "TSF-SHUF-" + UUID.randomUUID();
    ObjectNode created =
        orderCreated(externalOrderId, shopId, groupId.toString(), "PREPAID", "L-shuf", 2, 1);
    ObjectNode paid = orderPaid(externalOrderId, shopId, 2);
    ObjectNode body = JSON.createObjectNode();
    body.putArray("events").add(created).add(paid);
    JsonNode report = control("/control/events/shuffle", body);
    assertThat(report.path("sent")).hasSize(2);

    assertThat(worker.processAvailable(10)).isEqualTo(2);
    rewind(paid.path("event_id").asString());
    assertThat(worker.processAvailable(10)).isEqualTo(1);
    assertThat(
            fixture.inTenant(
                shop.tenant(),
                () ->
                    jdbc.queryForObject(
                        "SELECT fulfillment_status FROM sales_order WHERE external_order_id = ?",
                        String.class,
                        externalOrderId)))
        .isEqualTo("READY_TO_PICK");
    assertThat(
            fixture.inTenant(
                shop.tenant(),
                () ->
                    jdbc.queryForObject(
                        "SELECT payment_status FROM sales_order WHERE external_order_id = ?",
                        String.class,
                        externalOrderId)))
        .isEqualTo("PAID");
    assertThat(
            fixture.inTenant(
                shop.tenant(),
                () ->
                    jdbc.queryForObject(
                        """
                        SELECT count(*) FROM order_status_history
                        WHERE order_id = (SELECT id FROM sales_order WHERE external_order_id = ?)
                        """,
                        Long.class,
                        externalOrderId)))
        .isGreaterThan(0);
    assertThat(
            fixture.inTenant(
                shop.tenant(),
                () ->
                    jdbc.queryForObject(
                        """
                        SELECT count(*) FROM stock_reservation
                        WHERE reservation_group_id = ? AND status = 'ACTIVE' AND expires_at IS NOT NULL
                        """,
                        Long.class,
                        groupId)))
        .isZero();
  }

  @Test
  void repeatedCancelIsIdempotent() throws Exception {
    StockFixture.Shop shop = fixture.shop("ACTIVE");
    String shopId = fixture.tsfShopId(shop);
    UUID account = fixture.tsfChannelAccount(shop, "ACTIVE", "CONNECTED");
    UUID sku = fixture.sku(shop, 3);
    fixture.channelListing(shop, account, "L-rcn", sku, true);

    JsonNode checkout = checkoutViaControl("chk-rcn", shopId, "L-rcn", 1);
    String externalOrderId = "TSF-RCN-" + UUID.randomUUID();
    ingest(
        orderCreated(
            externalOrderId,
            shopId,
            checkout.path("reservation_id").asString(),
            "COD",
            "L-rcn",
            1,
            1));
    assertThat(worker.processAvailable(10)).isEqualTo(1);

    ObjectNode cancelled = orderCancelled(externalOrderId, shopId, 2);
    ObjectNode body = JSON.createObjectNode();
    body.put("times", 3);
    body.set("event", cancelled);
    JsonNode report = control("/control/events/repeat", body);
    assertThat(report.path("sent").get(0).path("http_status").asInt()).isEqualTo(202);
    assertThat(report.path("sent").get(1).path("http_status").asInt()).isEqualTo(200);
    assertThat(report.path("sent").get(2).path("http_status").asInt()).isEqualTo(200);

    assertThat(worker.processAvailable(10)).isEqualTo(1);
    assertThat(
            fixture.inTenant(
                shop.tenant(),
                () ->
                    jdbc.queryForObject(
                        "SELECT order_status FROM sales_order WHERE external_order_id = ?",
                        String.class,
                        externalOrderId)))
        .isEqualTo("CANCELLED");
    assertThat(
            count(
                "SELECT count(*) FROM inbox_event WHERE event_id = ?",
                cancelled.path("event_id").asString()))
        .isEqualTo(1);
  }

  @Test
  void paidAfterCancelOpensReconciliationIssue() throws Exception {
    StockFixture.Shop shop = fixture.shop("ACTIVE");
    String shopId = fixture.tsfShopId(shop);
    UUID account = fixture.tsfChannelAccount(shop, "ACTIVE", "CONNECTED");
    UUID sku = fixture.sku(shop, 2);
    fixture.channelListing(shop, account, "L-pac", sku, true);

    JsonNode checkout = checkoutViaControl("chk-pac", shopId, "L-pac", 1);
    String externalOrderId = "TSF-PAC-" + UUID.randomUUID();
    ingest(
        orderCreated(
            externalOrderId,
            shopId,
            checkout.path("reservation_id").asString(),
            "PREPAID",
            "L-pac",
            1,
            1));
    assertThat(worker.processAvailable(10)).isEqualTo(1);
    ingest(orderCancelled(externalOrderId, shopId, 2));
    assertThat(worker.processAvailable(10)).isEqualTo(1);

    ingest(orderPaid(externalOrderId, shopId, 3));
    assertThat(worker.processAvailable(10)).isEqualTo(1);
    assertThat(
            fixture.inTenant(
                shop.tenant(),
                () ->
                    jdbc.queryForObject(
                        """
                        SELECT count(*) FROM reconciliation_issue
                        WHERE rule = 'PAID_AFTER_CANCEL' AND status = 'OPEN'
                        """,
                        Long.class)))
        .isEqualTo(1);
  }

  @Test
  void shadowShortagePersistsValidShadowDiffJson() throws Exception {
    StockFixture.Shop shop = fixture.shop("ACTIVE");
    String shopId = fixture.tsfShopId(shop);
    UUID account = fixture.tsfChannelAccount(shop, "SHADOW", "CONNECTED");
    UUID sku = fixture.sku(shop, 1);
    fixture.channelListing(shop, account, "L-shd", sku, true);

    String externalOrderId = "TSF-SHD-" + UUID.randomUUID();
    ingest(
        orderCreated(externalOrderId, shopId, UuidV7.generate().toString(), "COD", "L-shd", 3, 1));
    assertThat(worker.processAvailable(10)).isEqualTo(1);

    String diffJson =
        fixture.inTenant(
            shop.tenant(),
            () ->
                jdbc.queryForObject(
                    "SELECT oms_value::text FROM shadow_diff WHERE ref = ?",
                    String.class,
                    externalOrderId));
    JsonNode diff = JSON.readTree(diffJson);
    assertThat(diff.path("shortfalls").isArray()).isTrue();
    assertThat(diff.path("shortfalls")).isNotEmpty();
    assertThat(diff.path("shortfalls").get(0).path("requested").asInt()).isEqualTo(3);
    assertThat(diff.path("shortfalls").get(0).path("available").asInt()).isLessThan(3);
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
  void orderUpdatedNoteIsLoggedAndRecipientCanChange() throws Exception {
    StockFixture.Shop shop = fixture.shop("ACTIVE");
    String shopId = fixture.tsfShopId(shop);
    UUID account = fixture.tsfChannelAccount(shop, "ACTIVE", "CONNECTED");
    UUID sku = fixture.sku(shop, 2);
    fixture.channelListing(shop, account, "L-upd", sku, true);

    JsonNode checkout = checkoutViaControl("chk-upd", shopId, "L-upd", 1);
    String externalOrderId = "TSF-UPD-" + UUID.randomUUID();
    ingest(
        orderCreated(
            externalOrderId,
            shopId,
            checkout.path("reservation_id").asString(),
            "COD",
            "L-upd",
            1,
            1));
    assertThat(worker.processAvailable(10)).isEqualTo(1);

    ObjectNode noteUpdate = orderUpdated(externalOrderId, shopId, 2);
    ((ObjectNode) noteUpdate.path("data")).remove("recipient");
    ((ObjectNode) noteUpdate.path("data")).put("note", "call before delivery");
    ingest(noteUpdate);
    assertThat(worker.processAvailable(10)).isEqualTo(1);
    assertThat(
            intakeLogs.list.stream()
                .anyMatch(
                    e ->
                        e.getFormattedMessage()
                            .contains(
                                "order.updated note ignored for external_order_id="
                                    + externalOrderId)))
        .isTrue();

    ObjectNode recipientUpdate = orderUpdated(externalOrderId, shopId, 3);
    ObjectNode recipient = JSON.createObjectNode();
    recipient.put("name", "Updated Name");
    recipient.put("phone", "0899999999");
    ObjectNode address = JSON.createObjectNode();
    address.put("line1", "new line");
    address.put("district", "district");
    address.put("province", "province");
    address.put("postcode", "10110");
    recipient.set("address", address);
    ((ObjectNode) recipientUpdate.path("data")).set("recipient", recipient);
    ingest(recipientUpdate);
    assertThat(worker.processAvailable(10)).isEqualTo(1);
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
    assertThat(province).isEqualTo("province");
  }

  @Test
  void repeatAndStaleDeliveriesAreSafeForCreated() throws Exception {
    StockFixture.Shop shop = fixture.shop("ACTIVE");
    String shopId = fixture.tsfShopId(shop);
    UUID account = fixture.tsfChannelAccount(shop, "ACTIVE", "CONNECTED");
    UUID sku = fixture.sku(shop, 2);
    fixture.channelListing(shop, account, "L-dup", sku, true);

    String externalOrderId = "TSF-DUP-" + UUID.randomUUID();
    ObjectNode created =
        orderCreated(externalOrderId, shopId, UuidV7.generate().toString(), "COD", "L-dup", 1, 1);
    ObjectNode repeatBody = JSON.createObjectNode();
    repeatBody.put("times", 2);
    repeatBody.set("event", created);
    JsonNode repeatReport = control("/control/events/repeat", repeatBody);
    assertThat(repeatReport.path("sent").get(1).path("http_status").asInt()).isEqualTo(200);

    ObjectNode staleBody = JSON.createObjectNode();
    staleBody.put("skew_seconds", 301);
    staleBody.set("event", created);
    JsonNode staleReport = control("/control/events/stale", staleBody);
    assertThat(staleReport.path("sent").get(0).path("http_status").asInt()).isEqualTo(401);

    assertThat(worker.processAvailable(10)).isEqualTo(1);
    assertThat(countSalesOrders(externalOrderId)).isEqualTo(1);
    assertThat(
            count(
                "SELECT count(*) FROM inbox_event WHERE event_id = ?",
                created.path("event_id").asString()))
        .isEqualTo(1);
  }

  @Test
  void disconnectedAccountSkipsStockAndCodIsReadyToPick() throws Exception {
    StockFixture.Shop shop = fixture.shop("ACTIVE");
    String shopId = fixture.tsfShopId(shop);
    UUID account = fixture.tsfChannelAccount(shop, "ACTIVE", "DISCONNECTED");
    UUID sku = fixture.sku(shop, 0);
    fixture.channelListing(shop, account, "L-disc", sku, true);

    String externalOrderId = "TSF-DISC-" + UUID.randomUUID();
    ingest(
        orderCreated(externalOrderId, shopId, UuidV7.generate().toString(), "COD", "L-disc", 1, 1));
    assertThat(worker.processAvailable(10)).isEqualTo(1);
    assertThat(
            fixture.inTenant(
                shop.tenant(),
                () -> jdbc.queryForObject("SELECT count(*) FROM stock_reservation", Long.class)))
        .isZero();
    assertThat(
            fixture.inTenant(
                shop.tenant(),
                () ->
                    jdbc.queryForObject(
                        "SELECT fulfillment_status FROM sales_order WHERE external_order_id = ?",
                        String.class,
                        externalOrderId)))
        .isEqualTo("READY_TO_PICK");
  }

  @Test
  void twoOrphanPaidsShareOneOpenReconciliationIssue() throws Exception {
    StockFixture.Shop shop = fixture.shop("ACTIVE");
    String shopId = fixture.tsfShopId(shop);
    UUID account = fixture.tsfChannelAccount(shop, "ACTIVE", "CONNECTED");
    UUID sku = fixture.sku(shop, 2);
    fixture.channelListing(shop, account, "L-2orph", sku, true);

    String externalA = "TSF-OR-A-" + UUID.randomUUID();
    String externalB = "TSF-OR-B-" + UUID.randomUUID();
    ObjectNode paidA = orderPaid(externalA, shopId, 1);
    ObjectNode paidB = orderPaid(externalB, shopId, 1);
    ingest(paidA);
    ingest(paidB);
    assertThat(worker.processAvailable(10)).isEqualTo(2);

    Instant old = Instant.now().minus(25, ChronoUnit.HOURS);
    backdateReceivedAt(paidA.path("event_id").asString(), old);
    backdateReceivedAt(paidB.path("event_id").asString(), old);
    rewind(paidA.path("event_id").asString());
    rewind(paidB.path("event_id").asString());
    assertThat(worker.processAvailable(1)).isEqualTo(1);
    assertThat(
            text(
                "SELECT status FROM inbox_event WHERE event_id = ?",
                paidA.path("event_id").asString()))
        .isEqualTo("FAILED");
    rewind(paidB.path("event_id").asString());
    assertThat(worker.processAvailable(1)).isEqualTo(1);
    assertThat(
            text(
                "SELECT status FROM inbox_event WHERE event_id = ?",
                paidB.path("event_id").asString()))
        .isEqualTo("FAILED");

    assertThat(
            fixture.inTenant(
                shop.tenant(),
                () ->
                    jdbc.queryForObject(
                        """
                        SELECT count(*) FROM reconciliation_issue
                        WHERE rule = 'ORDER_EVENT_WITHOUT_ORDER' AND status = 'OPEN'
                        """,
                        Long.class)))
        .isEqualTo(1);

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
    assertThat(details.path("count").asInt()).isEqualTo(2);
    assertThat(details.path("events").size()).isEqualTo(2);
  }

  @Test
  void duplicatePaidAfterCancelDoesNotOpenSecondIssue() throws Exception {
    StockFixture.Shop shop = fixture.shop("ACTIVE");
    String shopId = fixture.tsfShopId(shop);
    UUID account = fixture.tsfChannelAccount(shop, "ACTIVE", "CONNECTED");
    UUID sku = fixture.sku(shop, 2);
    fixture.channelListing(shop, account, "L-dpac", sku, true);

    JsonNode checkout = checkoutViaControl("chk-dpac", shopId, "L-dpac", 1);
    String externalOrderId = "TSF-DPAC-" + UUID.randomUUID();
    ingest(
        orderCreated(
            externalOrderId,
            shopId,
            checkout.path("reservation_id").asString(),
            "PREPAID",
            "L-dpac",
            1,
            1));
    assertThat(worker.processAvailable(10)).isEqualTo(1);
    ingest(orderCancelled(externalOrderId, shopId, 2));
    assertThat(worker.processAvailable(10)).isEqualTo(1);

    ingest(orderPaid(externalOrderId, shopId, 3));
    assertThat(worker.processAvailable(10)).isEqualTo(1);
    ingest(orderPaid(externalOrderId, shopId, 4));
    assertThat(worker.processAvailable(10)).isEqualTo(1);
    assertThat(
            fixture.inTenant(
                shop.tenant(),
                () ->
                    jdbc.queryForObject(
                        """
                        SELECT count(*) FROM reconciliation_issue
                        WHERE rule = 'PAID_AFTER_CANCEL' AND status = 'OPEN'
                        """,
                        Long.class)))
        .isEqualTo(1);
  }

  @Test
  void secondCancelWithNewEventIdReleasesNothing() throws Exception {
    StockFixture.Shop shop = fixture.shop("ACTIVE");
    String shopId = fixture.tsfShopId(shop);
    UUID account = fixture.tsfChannelAccount(shop, "ACTIVE", "CONNECTED");
    UUID sku = fixture.sku(shop, 3);
    fixture.channelListing(shop, account, "L-scn", sku, true);

    JsonNode checkout = checkoutViaControl("chk-scn", shopId, "L-scn", 2);
    String externalOrderId = "TSF-SCN-" + UUID.randomUUID();
    ingest(
        orderCreated(
            externalOrderId,
            shopId,
            checkout.path("reservation_id").asString(),
            "COD",
            "L-scn",
            2,
            1));
    assertThat(worker.processAvailable(10)).isEqualTo(1);
    assertThat(fixture.reserved(shop, sku)).isEqualTo(2);

    ingest(orderCancelled(externalOrderId, shopId, 2));
    assertThat(worker.processAvailable(10)).isEqualTo(1);
    assertThat(fixture.reserved(shop, sku)).isZero();

    ObjectNode secondCancel = orderCancelled(externalOrderId, shopId, 3);
    ingest(secondCancel);
    assertThat(worker.processAvailable(10)).isEqualTo(1);
    assertThat(fixture.reserved(shop, sku)).isZero();
  }

  @Test
  void blankReservationIdFreshReservesMappedLines() throws Exception {
    StockFixture.Shop shop = fixture.shop("ACTIVE");
    String shopId = fixture.tsfShopId(shop);
    UUID account = fixture.tsfChannelAccount(shop, "ACTIVE", "CONNECTED");
    UUID sku = fixture.sku(shop, 3);
    fixture.channelListing(shop, account, "L-blank", sku, true);

    String externalOrderId = "TSF-BLANK-" + UUID.randomUUID();
    ingest(orderCreated(externalOrderId, shopId, "   ", "COD", "L-blank", 2, 1));
    assertThat(worker.processAvailable(10)).isEqualTo(1);
    assertThat(fixture.reserved(shop, sku)).isEqualTo(2);
  }

  @Test
  void prepaidPaymentHistoryIncludesPendingToPaid() throws Exception {
    StockFixture.Shop shop = fixture.shop("ACTIVE");
    String shopId = fixture.tsfShopId(shop);
    UUID account = fixture.tsfChannelAccount(shop, "ACTIVE", "CONNECTED");
    UUID sku = fixture.sku(shop, 2);
    fixture.channelListing(shop, account, "L-hist", sku, true);

    JsonNode checkout = checkoutViaControl("chk-hist", shopId, "L-hist", 1);
    String externalOrderId = "TSF-HIST-" + UUID.randomUUID();
    ingest(
        orderCreated(
            externalOrderId,
            shopId,
            checkout.path("reservation_id").asString(),
            "PREPAID",
            "L-hist",
            1,
            1));
    assertThat(worker.processAvailable(10)).isEqualTo(1);
    ingest(orderPaid(externalOrderId, shopId, 2));
    assertThat(worker.processAvailable(10)).isEqualTo(1);
    assertThat(
            fixture.inTenant(
                shop.tenant(),
                () ->
                    jdbc.queryForObject(
                        """
                        SELECT count(*) FROM order_status_history h
                        JOIN sales_order o ON o.id = h.order_id
                        WHERE o.external_order_id = ?
                          AND h.dimension = 'PAYMENT'
                          AND h.from_value = 'PENDING'
                          AND h.to_value = 'PAID'
                        """,
                        Long.class,
                        externalOrderId)))
        .isEqualTo(1);
  }

  @Test
  void checkoutHandOffMixedControlAdoptsOnlyEnforcedSku() throws Exception {
    StockFixture.Shop shop = fixture.shop("ACTIVE");
    String shopId = fixture.tsfShopId(shop);
    UUID account = fixture.tsfChannelAccount(shop, "CONTROL", "CONNECTED");
    UUID skuCtrl = fixture.sku(shop, 6);
    UUID skuFree = fixture.sku(shop, 6);
    fixture.channelListing(shop, account, "L-mix-ctrl", skuCtrl, true);
    fixture.channelListing(shop, account, "L-mix-free", skuFree, false);

    JsonNode checkout = checkoutTwoLines(shopId, "chk-mix", "L-mix-ctrl", 1, "L-mix-free", 1);
    assertThat(checkout.path("enforced").asBoolean()).isFalse();

    String externalOrderId = "TSF-MIX-" + UUID.randomUUID();
    ingest(
        orderCreatedTwoLines(
            externalOrderId,
            shopId,
            checkout.path("reservation_id").asString(),
            "COD",
            "L-mix-ctrl",
            "L-mix-free",
            1,
            1,
            1));
    assertThat(worker.processAvailable(10)).isEqualTo(1);
    assertThat(fixture.reserved(shop, skuCtrl)).isEqualTo(1);
    assertThat(fixture.reserved(shop, skuFree)).isZero();
    assertNoActiveCheckoutReservations(shop);
    fixture.assertInvariants(shop);
  }

  @Test
  void checkoutHandOffAfterDeletedGroupFreshReservesOrderLines() throws Exception {
    StockFixture.Shop shop = fixture.shop("ACTIVE");
    String shopId = fixture.tsfShopId(shop);
    UUID account = fixture.tsfChannelAccount(shop, "ACTIVE", "CONNECTED");
    UUID sku = fixture.sku(shop, 8);
    fixture.channelListing(shop, account, "L-del", sku, true);

    JsonNode checkout = checkoutViaControl("chk-del", shopId, "L-del", 2);
    String reservationId = checkout.path("reservation_id").asString();
    deleteCheckoutReservation(reservationId);
    assertThat(fixture.reserved(shop, sku)).isZero();

    String externalOrderId = "TSF-DEL-" + UUID.randomUUID();
    ingest(orderCreated(externalOrderId, shopId, reservationId, "COD", "L-del", 2, 1));
    assertThat(worker.processAvailable(10)).isEqualTo(1);
    assertThat(fixture.reserved(shop, sku)).isEqualTo(2);
    fixture.assertInvariants(shop);
  }

  @Test
  void checkoutHandOffSurplusCheckoutQtyIsReleasedOnAdopt() throws Exception {
    StockFixture.Shop shop = fixture.shop("ACTIVE");
    String shopId = fixture.tsfShopId(shop);
    UUID account = fixture.tsfChannelAccount(shop, "ACTIVE", "CONNECTED");
    UUID sku = fixture.sku(shop, 10);
    fixture.channelListing(shop, account, "L-sur", sku, true);

    JsonNode checkout = checkoutViaControl("chk-sur", shopId, "L-sur", 3);
    String externalOrderId = "TSF-SUR-" + UUID.randomUUID();
    ingest(
        orderCreated(
            externalOrderId,
            shopId,
            checkout.path("reservation_id").asString(),
            "COD",
            "L-sur",
            1,
            1));
    assertThat(worker.processAvailable(10)).isEqualTo(1);
    assertThat(fixture.reserved(shop, sku)).isEqualTo(1);
    fixture.assertInvariants(shop);
  }

  @Test
  void checkoutHandOffBundleExplodesComponentsOnCreated() throws Exception {
    StockFixture.Shop shop = fixture.shop("ACTIVE");
    String shopId = fixture.tsfShopId(shop);
    UUID account = fixture.tsfChannelAccount(shop, "ACTIVE", "CONNECTED");
    UUID compA = fixture.sku(shop, 10);
    UUID compB = fixture.sku(shop, 10);
    UUID bundle = fixture.bundle(shop, Map.of(compA, 1, compB, 2));
    fixture.channelListing(shop, account, "L-bundle", bundle, true);

    JsonNode checkout = checkoutViaControl("chk-bnd", shopId, "L-bundle", 1);
    String externalOrderId = "TSF-BND-" + UUID.randomUUID();
    ingest(
        orderCreated(
            externalOrderId,
            shopId,
            checkout.path("reservation_id").asString(),
            "COD",
            "L-bundle",
            1,
            1));
    assertThat(worker.processAvailable(10)).isEqualTo(1);
    assertThat(fixture.reserved(shop, compA)).isEqualTo(1);
    assertThat(fixture.reserved(shop, compB)).isEqualTo(2);
    assertNoActiveCheckoutReservations(shop);
    fixture.assertInvariants(shop);
  }

  @Test
  void createdShortRestockPaidClearsOutOfStockHoldAndReachesReadyToPick() throws Exception {
    StockFixture.Shop shop = fixture.shop("ACTIVE");
    String shopId = fixture.tsfShopId(shop);
    UUID account = fixture.tsfChannelAccount(shop, "ACTIVE", "CONNECTED");
    UUID sku = fixture.sku(shop, 1);
    fixture.channelListing(shop, account, "L-restock", sku, true);

    String externalOrderId = "TSF-RST-" + UUID.randomUUID();
    ingest(
        orderCreated(
            externalOrderId, shopId, UuidV7.generate().toString(), "COD", "L-restock", 2, 1));
    assertThat(worker.processAvailable(10)).isEqualTo(1);
    assertThat(
            fixture.inTenant(
                shop.tenant(),
                () ->
                    jdbc.queryForObject(
                        "SELECT hold_reason FROM sales_order WHERE external_order_id = ?",
                        String.class,
                        externalOrderId)))
        .isEqualTo("OUT_OF_STOCK");

    fixture.receive(shop, sku, 5);
    ObjectNode paid = orderPaid(externalOrderId, shopId, 2);
    ingest(paid);
    assertThat(worker.processAvailable(10)).isEqualTo(1);
    assertThat(
            fixture.inTenant(
                shop.tenant(),
                () ->
                    jdbc.queryForObject(
                        """
                        SELECT fulfillment_status FROM sales_order
                        WHERE external_order_id = ? AND hold_reason = 'NONE'
                        """,
                        String.class,
                        externalOrderId)))
        .isEqualTo("READY_TO_PICK");
    fixture.assertInvariants(shop);
  }

  @Test
  void businessOversellMetricCountsActiveModeNotShadow() throws Exception {
    StockFixture.Shop activeShop = fixture.shop("ACTIVE");
    String activeShopId = fixture.tsfShopId(activeShop);
    UUID activeAccount = fixture.tsfChannelAccount(activeShop, "ACTIVE", "CONNECTED");
    UUID activeSku = fixture.sku(activeShop, 1);
    fixture.channelListing(activeShop, activeAccount, "L-ov-act", activeSku, true);
    double activeBefore = oversellMetric("ACTIVE");
    ingest(
        orderCreated(
            "TSF-OV-A-" + UUID.randomUUID(),
            activeShopId,
            UuidV7.generate().toString(),
            "COD",
            "L-ov-act",
            3,
            1));
    assertThat(worker.processAvailable(10)).isEqualTo(1);
    assertThat(oversellMetric("ACTIVE") - activeBefore).isEqualTo(1.0d);

    StockFixture.Shop shadowShop = fixture.shop("ACTIVE");
    String shadowShopId = fixture.tsfShopId(shadowShop);
    UUID shadowAccount = fixture.tsfChannelAccount(shadowShop, "SHADOW", "CONNECTED");
    UUID shadowSku = fixture.sku(shadowShop, 1);
    fixture.channelListing(shadowShop, shadowAccount, "L-ov-shd", shadowSku, true);
    double shadowBefore = oversellMetric("SHADOW");
    ingest(
        orderCreated(
            "TSF-OV-S-" + UUID.randomUUID(),
            shadowShopId,
            UuidV7.generate().toString(),
            "COD",
            "L-ov-shd",
            3,
            1));
    assertThat(worker.processAvailable(10)).isEqualTo(1);
    assertThat(oversellMetric("SHADOW") - shadowBefore).isZero();
  }

  @Test
  void orderLandsOnTsfAccountMatchingEnvelopeShopId() throws Exception {
    StockFixture.Shop shop = fixture.shop("ACTIVE");
    String shopId = fixture.tsfShopId(shop);
    UUID other =
        fixture.inTenant(
            shop.tenant(),
            () -> {
              UUID id = UuidV7.generate();
              jdbc.update(
                  """
                  INSERT INTO channel_account (id, tenant_id, channel, external_shop_id, status, mode, created_at)
                  VALUES (?, ?, 'TSF', ?, 'CONNECTED', 'OBSERVE', now() - interval '2 days')
                  """,
                  id,
                  shop.tenant(),
                  "other-" + id);
              return id;
            });
    UUID matching = fixture.tsfChannelAccount(shop, "ACTIVE", "CONNECTED");
    UUID sku = fixture.sku(shop, 3);
    fixture.channelListing(shop, matching, "L-acct", sku, true);
    fixture.channelListing(shop, other, "L-other", sku, true);

    String externalOrderId = "TSF-ACCT-" + UUID.randomUUID();
    ingest(
        orderCreated(externalOrderId, shopId, UuidV7.generate().toString(), "COD", "L-acct", 1, 1));
    assertThat(worker.processAvailable(10)).isEqualTo(1);
    UUID channelAccountId =
        fixture.inTenant(
            shop.tenant(),
            () ->
                jdbc.queryForObject(
                    "SELECT channel_account_id FROM sales_order WHERE external_order_id = ?",
                    UUID.class,
                    externalOrderId));
    assertThat(channelAccountId).isEqualTo(matching);
    assertThat(channelAccountId).isNotEqualTo(other);
  }

  @Test
  void paidAtAndOrderedAtFollowEnvelopeOccurredAt() throws Exception {
    StockFixture.Shop shop = fixture.shop("ACTIVE");
    String shopId = fixture.tsfShopId(shop);
    UUID account = fixture.tsfChannelAccount(shop, "ACTIVE", "CONNECTED");
    UUID sku = fixture.sku(shop, 5);
    fixture.channelListing(shop, account, "L-ts", sku, true);

    Instant createdAt = Instant.parse("2026-03-15T10:00:00Z");
    Instant paidAt = Instant.parse("2026-03-16T12:30:00Z");
    String externalOrderId = "TSF-TS-" + UUID.randomUUID();
    ObjectNode created =
        orderCreated(
            externalOrderId, shopId, UuidV7.generate().toString(), "PREPAID", "L-ts", 1, 1);
    created.put("occurred_at", createdAt.toString());
    ((ObjectNode) created.path("data")).remove("ordered_at");
    ingest(created);
    assertThat(worker.processAvailable(10)).isEqualTo(1);

    ObjectNode paid = orderPaid(externalOrderId, shopId, 2);
    paid.put("occurred_at", paidAt.toString());
    ingest(paid);
    assertThat(worker.processAvailable(10)).isEqualTo(1);

    Instant storedOrdered =
        fixture.inTenant(
            shop.tenant(),
            () ->
                jdbc.queryForObject(
                    "SELECT ordered_at FROM sales_order WHERE external_order_id = ?",
                    (rs, row) -> rs.getTimestamp("ordered_at").toInstant(),
                    externalOrderId));
    Instant storedPaid =
        fixture.inTenant(
            shop.tenant(),
            () ->
                jdbc.queryForObject(
                    "SELECT paid_at FROM sales_order WHERE external_order_id = ?",
                    (rs, row) -> rs.getTimestamp("paid_at").toInstant(),
                    externalOrderId));
    assertThat(storedOrdered).isEqualTo(createdAt);
    assertThat(storedPaid).isEqualTo(paidAt);
  }

  @Test
  void redactAfterNullAtIntakeAndScheduledOnCancel() throws Exception {
    StockFixture.Shop shop = fixture.shop("ACTIVE");
    String shopId = fixture.tsfShopId(shop);
    UUID account = fixture.tsfChannelAccount(shop, "ACTIVE", "CONNECTED");
    UUID sku = fixture.sku(shop, 2);
    fixture.channelListing(shop, account, "L-red", sku, true);

    JsonNode checkout = checkoutViaControl("chk-red", shopId, "L-red", 1);
    String externalOrderId = "TSF-RED-" + UUID.randomUUID();
    ingest(
        orderCreated(
            externalOrderId,
            shopId,
            checkout.path("reservation_id").asString(),
            "COD",
            "L-red",
            1,
            1));
    assertThat(worker.processAvailable(10)).isEqualTo(1);
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
                        "SELECT redact_after FROM order_recipient WHERE order_id = ?",
                        Object.class,
                        orderId)))
        .isNull();

    Instant beforeCancel = Instant.now();
    ingest(orderCancelled(externalOrderId, shopId, 2));
    assertThat(worker.processAvailable(10)).isEqualTo(1);
    Instant redactAfter =
        fixture.inTenant(
            shop.tenant(),
            () ->
                jdbc.queryForObject(
                    "SELECT redact_after FROM order_recipient WHERE order_id = ?",
                    Instant.class,
                    orderId));
    assertThat(redactAfter).isAfter(beforeCancel.plus(89, ChronoUnit.DAYS));
    assertThat(redactAfter).isBefore(beforeCancel.plus(91, ChronoUnit.DAYS));
  }

  @Test
  void nonThbCurrencyFailsWithoutRetryStorm() throws Exception {
    StockFixture.Shop shop = fixture.shop("ACTIVE");
    String shopId = fixture.tsfShopId(shop);
    UUID account = fixture.tsfChannelAccount(shop, "ACTIVE", "CONNECTED");
    UUID sku = fixture.sku(shop, 2);
    fixture.channelListing(shop, account, "L-usd", sku, true);

    ObjectNode created =
        orderCreated(
            "TSF-USD-" + UUID.randomUUID(),
            shopId,
            UuidV7.generate().toString(),
            "COD",
            "L-usd",
            1,
            1);
    ((ObjectNode) created.path("data")).put("currency", "USD");
    ingest(created);
    assertThat(worker.processAvailable(10)).isEqualTo(1);
    String eventId = created.path("event_id").asString();
    assertThat(text("SELECT status FROM inbox_event WHERE event_id = ?", eventId))
        .isEqualTo("DEAD");
    assertThat(text("SELECT attempts FROM inbox_event WHERE event_id = ?", eventId)).isEqualTo("1");
  }

  @Test
  void orderUpdatedRotatesCiphertextPhoneHashAndLogsNoPii() throws Exception {
    StockFixture.Shop shop = fixture.shop("ACTIVE");
    String shopId = fixture.tsfShopId(shop);
    UUID account = fixture.tsfChannelAccount(shop, "ACTIVE", "CONNECTED");
    UUID sku = fixture.sku(shop, 2);
    fixture.channelListing(shop, account, "L-pii", sku, true);

    JsonNode checkout = checkoutViaControl("chk-pii", shopId, "L-pii", 1);
    String externalOrderId = "TSF-PII-" + UUID.randomUUID();
    ingest(
        orderCreated(
            externalOrderId,
            shopId,
            checkout.path("reservation_id").asString(),
            "COD",
            "L-pii",
            1,
            1));
    assertThat(worker.processAvailable(10)).isEqualTo(1);
    UUID orderId =
        fixture.inTenant(
            shop.tenant(),
            () ->
                jdbc.queryForObject(
                    "SELECT id FROM sales_order WHERE external_order_id = ?",
                    UUID.class,
                    externalOrderId));
    byte[] phoneBefore =
        fixture.inTenant(
            shop.tenant(),
            () ->
                jdbc.queryForObject(
                    "SELECT phone_enc FROM order_recipient WHERE order_id = ?",
                    byte[].class,
                    orderId));
    byte[] hashBefore =
        fixture.inTenant(
            shop.tenant(),
            () ->
                jdbc.queryForObject(
                    "SELECT phone_hash FROM order_recipient WHERE order_id = ?",
                    byte[].class,
                    orderId));
    StoredRecipient before =
        fixture.inTenant(shop.tenant(), () -> recipients.find(orderId).orElseThrow());

    intakeLogs.list.clear();
    ObjectNode recipientUpdate = orderUpdated(externalOrderId, shopId, 2);
    ObjectNode recipient = JSON.createObjectNode();
    recipient.put("name", "New Legal Name");
    recipient.put("phone", "0891112233");
    ObjectNode address = JSON.createObjectNode();
    address.put("line1", "changed line");
    address.put("district", "d");
    address.put("province", "Chiang Mai");
    address.put("postcode", "50000");
    recipient.set("address", address);
    ((ObjectNode) recipientUpdate.path("data")).set("recipient", recipient);
    ingest(recipientUpdate);
    assertThat(worker.processAvailable(10)).isEqualTo(1);

    byte[] phoneAfter =
        fixture.inTenant(
            shop.tenant(),
            () ->
                jdbc.queryForObject(
                    "SELECT phone_enc FROM order_recipient WHERE order_id = ?",
                    byte[].class,
                    orderId));
    byte[] hashAfter =
        fixture.inTenant(
            shop.tenant(),
            () ->
                jdbc.queryForObject(
                    "SELECT phone_hash FROM order_recipient WHERE order_id = ?",
                    byte[].class,
                    orderId));
    StoredRecipient after =
        fixture.inTenant(shop.tenant(), () -> recipients.find(orderId).orElseThrow());

    assertThat(phoneBefore).isNotNull();
    assertThat(phoneAfter).isNotNull();
    assertThat(Arrays.equals(phoneBefore, phoneAfter)).isFalse();
    assertThat(Arrays.equals(hashBefore, hashAfter)).isFalse();
    assertThat(after.phone()).isEqualTo("0891112233");
    assertThat(after.province()).isEqualTo("Chiang Mai");
    assertThat(before.phone()).isNotEqualTo(after.phone());

    String logBlob =
        intakeLogs.list.stream()
            .map(ILoggingEvent::getFormattedMessage)
            .reduce("", (a, b) -> a + "\n" + b);
    assertThat(logBlob).doesNotContain("0891112233");
    assertThat(logBlob).doesNotContain(before.phone());
    assertThat(logBlob).doesNotContain("New Legal Name");
  }

  @Test
  void tenantsIsolateSameExternalOrderIdAndForeignReservationId() throws Exception {
    StockFixture.Shop shopA = fixture.shop("ACTIVE");
    StockFixture.Shop shopB = fixture.shop("ACTIVE");
    String shopIdA = fixture.tsfShopId(shopA);
    String shopIdB = fixture.tsfShopId(shopB);
    UUID accountA = fixture.tsfChannelAccount(shopA, "ACTIVE", "CONNECTED");
    UUID accountB = fixture.tsfChannelAccount(shopB, "ACTIVE", "CONNECTED");
    UUID skuA = fixture.sku(shopA, 3);
    UUID skuB = fixture.sku(shopB, 3);
    fixture.channelListing(shopA, accountA, "L-iso-a", skuA, true);
    fixture.channelListing(shopB, accountB, "L-iso-b", skuB, true);

    JsonNode checkoutB = checkoutViaControl("chk-iso-b", shopIdB, "L-iso-b", 2);
    String sharedExternal = "TSF-SHARED-" + UUID.randomUUID();

    ingest(
        orderCreated(
            sharedExternal,
            shopIdA,
            checkoutB.path("reservation_id").asString(),
            "COD",
            "L-iso-a",
            2,
            1));
    assertThat(worker.processAvailable(10)).isEqualTo(1);
    assertThat(countSalesOrders(sharedExternal)).isEqualTo(1);
    assertThat(fixture.reserved(shopA, skuA)).isEqualTo(2);
    assertThat(
            fixture.inTenant(
                shopB.tenant(),
                () ->
                    jdbc.queryForObject(
                        """
                        SELECT count(*) FROM stock_reservation
                        WHERE owner_type = 'ORDER' AND status = 'ACTIVE'
                        """,
                        Long.class)))
        .isZero();

    ingest(
        orderCreated(
            sharedExternal, shopIdB, UuidV7.generate().toString(), "COD", "L-iso-b", 2, 1));
    assertThat(worker.processAvailable(10)).isEqualTo(1);
    assertThat(
            fixture.inTenant(
                shopB.tenant(),
                () ->
                    jdbc.queryForObject(
                        "SELECT count(*) FROM sales_order WHERE external_order_id = ?",
                        Long.class,
                        sharedExternal)))
        .isEqualTo(1);
    assertThat(fixture.reserved(shopB, skuB)).isEqualTo(2);
  }

  private JsonNode checkoutTwoLines(
      String shopId, String checkoutId, String listingA, int qtyA, String listingB, int qtyB)
      throws Exception {
    ObjectNode body = JSON.createObjectNode();
    body.put("checkout_id", checkoutId);
    body.put("tsf_shop_id", shopId);
    ArrayNode items = JSON.createArrayNode();
    ObjectNode itemA = JSON.createObjectNode();
    itemA.put("listing_sku_id", listingA);
    itemA.put("qty", qtyA);
    items.add(itemA);
    ObjectNode itemB = JSON.createObjectNode();
    itemB.put("listing_sku_id", listingB);
    itemB.put("qty", qtyB);
    items.add(itemB);
    body.set("items", items);
    JsonNode report = control("/control/checkout/reservations", body);
    assertThat(report.path("oms_status").asInt()).isEqualTo(201);
    return JSON.readTree(report.path("oms_body").asString());
  }

  private ObjectNode orderCreatedTwoLines(
      String orderId,
      String shopId,
      String reservationId,
      String paymentMethod,
      String listingA,
      String listingB,
      int qtyA,
      int qtyB,
      long aggregateVersion)
      throws IOException {
    return OrderIntakeScenarioSupport.orderCreatedTwoLines(
        JSON,
        orderId,
        shopId,
        reservationId,
        paymentMethod,
        listingA,
        listingB,
        qtyA,
        qtyB,
        aggregateVersion);
  }

  private void deleteCheckoutReservation(String reservationId) throws Exception {
    HttpResponse<String> response =
        HTTP.send(
            HttpRequest.newBuilder(
                    URI.create(
                        "http://127.0.0.1:"
                            + OrderIntakeMockRuntime.mockPort()
                            + "/control/checkout/reservations/"
                            + reservationId))
                .timeout(HTTP_TIMEOUT)
                .DELETE()
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(JSON.readTree(response.body()).path("response_schema_valid").asBoolean()).isTrue();
  }

  private void assertNoActiveCheckoutReservations(StockFixture.Shop shop) {
    fixture.inTenant(
        shop.tenant(),
        () -> {
          assertThat(
                  jdbc.queryForObject(
                      """
                      SELECT count(*) FROM stock_reservation
                      WHERE owner_type = 'CHECKOUT' AND status = 'ACTIVE'
                      """,
                      Long.class))
              .isZero();
          return null;
        });
  }

  private double oversellMetric(String mode) {
    return Optional.ofNullable(
            meters.find(OrderIntakeSupport.BUSINESS_OVERSELL_METRIC).tag("mode", mode).counter())
        .map(Counter::count)
        .orElse(0d);
  }

  private JsonNode checkoutViaControl(String checkoutId, String shopId, String listingSku, int qty)
      throws Exception {
    ObjectNode body = JSON.createObjectNode();
    body.put("checkout_id", checkoutId);
    body.put("tsf_shop_id", shopId);
    ArrayNode items = JSON.createArrayNode();
    ObjectNode item = JSON.createObjectNode();
    item.put("listing_sku_id", listingSku);
    item.put("qty", qty);
    items.add(item);
    body.set("items", items);
    JsonNode report = control("/control/checkout/reservations", body);
    assertThat(report.path("oms_status").asInt()).isEqualTo(201);
    return JSON.readTree(report.path("oms_body").asString());
  }

  private void deliverAfterReservationExpiry(Instant reservationExpiresAt, ObjectNode event)
      throws Exception {
    ObjectNode body = JSON.createObjectNode();
    body.put("reservation_expires_at", reservationExpiresAt.toString());
    body.set("event", event);
    JsonNode report = control("/control/events/after-reservation-expiry", body);
    assertThat(report.path("sent").get(0).path("http_status").asInt()).isEqualTo(202);
  }

  private JsonNode control(String path, ObjectNode body) throws Exception {
    HttpResponse<String> response =
        HTTP.send(
            HttpRequest.newBuilder(
                    URI.create("http://127.0.0.1:" + OrderIntakeMockRuntime.mockPort() + path))
                .timeout(HTTP_TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    assertThat(response.statusCode()).isEqualTo(200);
    return JSON.readTree(response.body());
  }

  private void backdateCheckoutExpiry(StockFixture.Shop shop, UUID groupId) {
    fixture.inTenant(
        shop.tenant(),
        () ->
            jdbc.update(
                """
                UPDATE stock_reservation
                SET expires_at = now() - interval '1 minute'
                WHERE reservation_group_id = ? AND owner_type = 'CHECKOUT' AND status = 'ACTIVE'
                """,
                groupId));
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

  private void attachIntakeLogs() {
    intakeLogs.start();
    for (String name :
        List.of(
            OrderIntakeSupport.class.getName(),
            InboxWorker.class.getName(),
            org.slf4j.Logger.ROOT_LOGGER_NAME)) {
      ch.qos.logback.classic.Logger logger =
          (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(name);
      logger.addAppender(intakeLogs);
    }
  }

  private void detachIntakeLogs() {
    for (String name :
        List.of(
            OrderIntakeSupport.class.getName(),
            InboxWorker.class.getName(),
            org.slf4j.Logger.ROOT_LOGGER_NAME)) {
      ch.qos.logback.classic.Logger logger =
          (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(name);
      logger.detachAppender(intakeLogs);
    }
    intakeLogs.list.clear();
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

  private ObjectNode orderCreated(
      String orderId,
      String shopId,
      String reservationId,
      String paymentMethod,
      String listingSku,
      int qty,
      long aggregateVersion)
      throws IOException {
    ObjectNode event = loadExample("order.created.json");
    event.put("event_id", "evt-" + UUID.randomUUID());
    event.put("tsf_shop_id", shopId);
    event.put("aggregate_id", orderId);
    event.put("aggregate_version", aggregateVersion);
    event.put("occurred_at", Instant.now().truncatedTo(ChronoUnit.SECONDS).toString());
    ObjectNode data = (ObjectNode) event.get("data");
    data.put("order_id", orderId);
    String rid =
        reservationId == null || reservationId.isBlank()
            ? UuidV7.generate().toString()
            : reservationId;
    data.put("reservation_id", rid);
    data.put("payment_method", paymentMethod);
    if ("PREPAID".equals(paymentMethod)) {
      data.put("payment_expires_at", Instant.now().plus(30, ChronoUnit.MINUTES).toString());
    }
    ArrayNode lines = JSON.createArrayNode();
    ObjectNode line = JSON.createObjectNode();
    line.put("line_id", "L1");
    line.put("listing_sku_id", listingSku);
    line.put("seller_sku", "SKU-1");
    line.put("name", "Item");
    line.put("qty", qty);
    line.put("unit_price", 100);
    lines.add(line);
    data.set("lines", lines);
    assertThat(CONTRACT.envelopeErrors(JSON.writeValueAsString(event))).isEmpty();
    return event;
  }

  private ObjectNode orderPaid(String orderId, String shopId, long aggregateVersion)
      throws IOException {
    ObjectNode event = loadExample("order.paid.json");
    event.put("event_id", "evt-" + UUID.randomUUID());
    event.put("tsf_shop_id", shopId);
    event.put("aggregate_id", orderId);
    event.put("aggregate_version", aggregateVersion);
    event.put("occurred_at", Instant.now().truncatedTo(ChronoUnit.SECONDS).toString());
    ((ObjectNode) event.get("data")).put("order_id", orderId);
    assertThat(CONTRACT.envelopeErrors(JSON.writeValueAsString(event))).isEmpty();
    return event;
  }

  private ObjectNode orderCancelled(String orderId, String shopId, long aggregateVersion)
      throws IOException {
    ObjectNode event = loadExample("order.cancelled.json");
    event.put("event_id", "evt-" + UUID.randomUUID());
    event.put("tsf_shop_id", shopId);
    event.put("aggregate_id", orderId);
    event.put("aggregate_version", aggregateVersion);
    event.put("occurred_at", Instant.now().truncatedTo(ChronoUnit.SECONDS).toString());
    ((ObjectNode) event.get("data")).put("order_id", orderId);
    assertThat(CONTRACT.envelopeErrors(JSON.writeValueAsString(event))).isEmpty();
    return event;
  }

  private ObjectNode orderUpdated(String orderId, String shopId, long aggregateVersion)
      throws IOException {
    ObjectNode event = loadExample("order.updated.json");
    event.put("event_id", "evt-" + UUID.randomUUID());
    event.put("tsf_shop_id", shopId);
    event.put("aggregate_id", orderId);
    event.put("aggregate_version", aggregateVersion);
    event.put("occurred_at", Instant.now().truncatedTo(ChronoUnit.SECONDS).toString());
    ((ObjectNode) event.get("data")).put("order_id", orderId);
    assertThat(CONTRACT.envelopeErrors(JSON.writeValueAsString(event))).isEmpty();
    return event;
  }

  private static ObjectNode loadExample(String name) throws IOException {
    try (InputStream in =
        MockTsfApplication.class.getResourceAsStream("/contracts/examples/events/" + name)) {
      if (in == null) {
        throw new IllegalStateException("missing example " + name);
      }
      return (ObjectNode) JSON.readTree(in);
    }
  }

  private long countSalesOrders(String externalOrderId) throws Exception {
    try (Connection admin = AuthTestSupport.admin();
        PreparedStatement statement =
            admin.prepareStatement(
                "SELECT count(*) FROM sales_order WHERE external_order_id = ?")) {
      statement.setString(1, externalOrderId);
      try (ResultSet rows = statement.executeQuery()) {
        assertThat(rows.next()).isTrue();
        return rows.getLong(1);
      }
    }
  }

  private long tableCount(String table) throws Exception {
    try (Connection admin = AuthTestSupport.admin();
        var statement = admin.createStatement();
        var rows = statement.executeQuery("SELECT count(*) FROM " + table)) {
      assertThat(rows.next()).isTrue();
      return rows.getLong(1);
    }
  }

  private long outboxCount() throws Exception {
    return tableCount("outbox_event");
  }

  private void rewind(String eventId) throws Exception {
    try (Connection admin = AuthTestSupport.admin();
        PreparedStatement statement =
            admin.prepareStatement(
                "UPDATE inbox_event SET next_attempt_at = now() - interval '1 second', "
                    + "status = CASE WHEN status = 'FAILED' THEN 'RECEIVED' ELSE status END "
                    + "WHERE event_id = ?")) {
      statement.setString(1, eventId);
      assertThat(statement.executeUpdate()).isEqualTo(1);
    }
  }

  private static long count(String sql, String arg) throws Exception {
    try (Connection admin = AuthTestSupport.admin();
        PreparedStatement statement = admin.prepareStatement(sql)) {
      if (arg != null) {
        statement.setString(1, arg);
      }
      try (ResultSet rows = statement.executeQuery()) {
        assertThat(rows.next()).isTrue();
        return rows.getLong(1);
      }
    }
  }

  private static String text(String sql, String arg) throws Exception {
    try (Connection admin = AuthTestSupport.admin();
        PreparedStatement statement = admin.prepareStatement(sql)) {
      statement.setString(1, arg);
      try (ResultSet rows = statement.executeQuery()) {
        assertThat(rows.next()).isTrue();
        return rows.getString(1);
      }
    }
  }

  private static String tsfToken() {
    return mock().getBean(TokenIssuer.class).tsfServiceToken();
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

  @TestConfiguration
  static class IntakeTestConfig {

    static final AtomicBoolean failAfterOutbox = new AtomicBoolean(false);
    static final AtomicInteger afterOutboxCalls = new AtomicInteger();

    @Bean
    @Primary
    OrderIntakeHooks orderIntakeHooks(JdbcTemplate jdbc) {
      return new OrderIntakeHooks() {
        @Override
        public void beforeEngineWrite() {
          OrderIntakeFaultTestConfig.maybeInjectDeadlock(jdbc);
        }

        @Override
        public void afterOutbox() {
          afterOutboxCalls.incrementAndGet();
          if (failAfterOutbox.getAndSet(false)) {
            throw new IllegalStateException("afterOutbox failed");
          }
        }
      };
    }
  }
}
