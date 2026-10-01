package com.thaishopfun.oms.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.thaishopfun.mocktsf.MockTsfApplication;
import com.thaishopfun.mocktsf.OmsEndpoint;
import com.thaishopfun.mocktsf.contract.ContractValidator;
import com.thaishopfun.oms.auth.AuthTestSupport;
import com.thaishopfun.oms.inbox.InboxWorker;
import com.thaishopfun.oms.outbox.OutboxPublisher;
import com.thaishopfun.oms.stock.StockFixture;
import com.thaishopfun.oms.tenant.TenantContext;
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
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
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

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(OrderIntakeAcceptanceTest.IntakeTestConfig.class)
class OrderIntakeAcceptanceTest {

  private static final String ISSUER = "http://mock-tsf.test/tsf-idp";
  private static final String INBOX_SECRET = "dev-inbox-hmac-secret";
  private static final String OUTBOX_SECRET = "dev-outbox-webhook-secret-local-only";
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final HttpClient HTTP =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
  private static final ContractValidator CONTRACT = ContractValidator.classpath();

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
    registry.add("oms.security.internal-client-ids", () -> "tsf,tsf-checkout");
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

  @Autowired InboxWorker worker;
  @Autowired OutboxPublisher publisher;
  @Autowired JdbcTemplate jdbc;
  @Autowired PlatformTransactionManager transactions;

  StockFixture fixture;

  @BeforeEach
  void setup() throws Exception {
    mock.getBean(OmsEndpoint.class).setBaseUrl("http://127.0.0.1:" + port);
    fixture = new StockFixture(jdbc, transactions);
    IntakeTestConfig.failAfterOutbox.set(false);
    IntakeTestConfig.afterOutboxCalls.set(0);
    try (Connection admin = AuthTestSupport.admin();
        var statement = admin.createStatement()) {
      statement.execute(
          "TRUNCATE TABLE outbox_event, inbox_event, order_status_history, order_line, "
              + "order_recipient, sales_order, stock_reservation, stock_ledger, reconciliation_issue");
    }
  }

  @AfterEach
  void clearTenant() {
    TenantContext.clear();
  }

  @Test
  void codOrderGoesReadyToPickAndPublishesStatusChanged() throws Exception {
    StockFixture.Shop shop = fixture.shop("ACTIVE");
    String shopId = fixture.tsfShopId(shop);
    UUID account = fixture.tsfChannelAccount(shop, "ACTIVE", "CONNECTED");
    UUID sku = fixture.sku(shop, 5);
    fixture.channelListing(shop, account, "L-cod", sku, true);

    // Step 1: Checkout hand-off reservation for the mapped line.
    HttpResponse<String> checkout =
        checkoutPost("chk-cod", shopId, "L-cod", 2, "cod-reserve-" + UUID.randomUUID());
    assertThat(checkout.statusCode()).isEqualTo(201);
    String reservationId = JSON.readTree(checkout.body()).path("reservation_id").asString();

    String externalOrderId = "TSF-COD-" + UUID.randomUUID();
    ObjectNode created =
        orderCreated(externalOrderId, shopId, reservationId, "COD", "L-cod", 2, 1);
    ingest(created);

    // Step 2: Inbox intake adopts the checkout hold and moves fulfillment to READY_TO_PICK.
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

    // Step 3: One outbox row, contract-valid, delivered to mock-tsf.
    String payload =
        fixture.inTenant(
            shop.tenant(),
            () ->
                jdbc.queryForObject(
                    "SELECT payload::text FROM outbox_event WHERE event_type = 'order.status_changed'",
                    String.class));
    assertThat(CONTRACT.envelopeErrors(payload)).isEmpty();
    assertThat(publisher.publishOnce()).isEqualTo(1);

    JsonNode received =
        JSON.readTree(
            HTTP.send(
                    HttpRequest.newBuilder(
                            URI.create(
                                "http://127.0.0.1:" + mockPort() + "/control/received-events"))
                        .GET()
                        .build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .body());
    long statusChanged =
        received.path("events").valueStream().filter(n -> matchesStatusChanged(n, externalOrderId))
            .count();
    assertThat(statusChanged).isEqualTo(1);
  }

  @Test
  void checkoutHandOffThenCreatedAdoptsOrderOwnerRows() throws Exception {
    StockFixture.Shop shop = fixture.shop("ACTIVE");
    String shopId = fixture.tsfShopId(shop);
    UUID account = fixture.tsfChannelAccount(shop, "ACTIVE", "CONNECTED");
    UUID sku = fixture.sku(shop, 8);
    fixture.channelListing(shop, account, "L-handoff", sku, true);

    // Step 1: Reserve at checkout under CHECKOUT owner.
    HttpResponse<String> checkout =
        checkoutPost("chk-handoff", shopId, "L-handoff", 3, "handoff-" + UUID.randomUUID());
    String reservationId = JSON.readTree(checkout.body()).path("reservation_id").asString();
    assertThat(
            fixture.inTenant(
                shop.tenant(),
                () ->
                    jdbc.queryForObject(
                        "SELECT count(*) FROM stock_reservation WHERE owner_type = 'CHECKOUT'",
                        Long.class)))
        .isEqualTo(1);

    String externalOrderId = "TSF-HO-" + UUID.randomUUID();
    ingest(orderCreated(externalOrderId, shopId, reservationId, "COD", "L-handoff", 3, 1));

    // Step 2: order.created transfers the group to ORDER owner for the new sales order.
    assertThat(worker.processAvailable(10)).isEqualTo(1);
    UUID orderId =
        fixture.inTenant(
            shop.tenant(),
            () ->
                jdbc.queryForObject(
                    "SELECT id FROM sales_order WHERE external_order_id = ?",
                    UUID.class,
                    externalOrderId));
    long orderRows =
        fixture.inTenant(
            shop.tenant(),
            () ->
                jdbc.queryForObject(
                    """
                    SELECT count(*) FROM stock_reservation
                    WHERE owner_type = 'ORDER' AND owner_ref = ? AND status = 'ACTIVE'
                    """,
                    Long.class,
                    orderId.toString()));
    assertThat(orderRows).isEqualTo(1);
    assertThat(fixture.reserved(shop, sku)).isEqualTo(3);
    fixture.assertInvariants(shop);
  }

  @Test
  void atomicityRollsBackWhenAfterOutboxHookFailsThenSucceeds() throws Exception {
    StockFixture.Shop shop = fixture.shop("ACTIVE");
    String shopId = fixture.tsfShopId(shop);
    UUID account = fixture.tsfChannelAccount(shop, "ACTIVE", "CONNECTED");
    UUID sku = fixture.sku(shop, 4);
    fixture.channelListing(shop, account, "L-atom", sku, true);

    HttpResponse<String> checkout =
        checkoutPost("chk-atom", shopId, "L-atom", 2, "atom-" + UUID.randomUUID());
    String reservationId = JSON.readTree(checkout.body()).path("reservation_id").asString();
    String externalOrderId = "TSF-AT-" + UUID.randomUUID();
    ObjectNode created =
        orderCreated(externalOrderId, shopId, reservationId, "COD", "L-atom", 2, 1);
    ingest(created);
    String eventId = created.path("event_id").asString();

    // Step 1: First attempt fails in afterOutbox; handler transaction rolls back the order.
    IntakeTestConfig.failAfterOutbox.set(true);
    assertThat(worker.processAvailable(10)).isEqualTo(1);
    assertThat(countSalesOrders(externalOrderId)).isZero();
    assertThat(outboxCount()).isZero();
    assertThat(text("SELECT status FROM inbox_event WHERE event_id = ?", eventId)).isEqualTo("FAILED");

    // Step 2: Retry succeeds with the hook disabled.
    IntakeTestConfig.failAfterOutbox.set(false);
    rewind(eventId);
    assertThat(worker.processAvailable(10)).isEqualTo(1);
    assertThat(countSalesOrders(externalOrderId)).isEqualTo(1);
    assertThat(outboxCount()).isEqualTo(1);
    assertThat(text("SELECT status FROM inbox_event WHERE event_id = ?", eventId))
        .isEqualTo("PROCESSED");
  }

  @Test
  void paidBeforeCreatedDefersWithoutBurningAttemptThenCompletes() throws Exception {
    StockFixture.Shop shop = fixture.shop("ACTIVE");
    String shopId = fixture.tsfShopId(shop);
    UUID account = fixture.tsfChannelAccount(shop, "ACTIVE", "CONNECTED");
    UUID sku = fixture.sku(shop, 6);
    fixture.channelListing(shop, account, "L-paid", sku, true);

    String externalOrderId = "TSF-PD-" + UUID.randomUUID();
    ObjectNode paid = orderPaid(externalOrderId, shopId, 2);
    ingest(paid);
    String paidEventId = paid.path("event_id").asString();

    // Step 1: Paid arrives before created; worker defers without incrementing attempts.
    assertThat(worker.processAvailable(10)).isEqualTo(1);
    assertThat(text("SELECT status FROM inbox_event WHERE event_id = ?", paidEventId))
        .isEqualTo("RECEIVED");
    assertThat(count("SELECT attempts FROM inbox_event WHERE event_id = ?", paidEventId)).isZero();

    // Step 2: Created provisions the order; paid applies on the next pass.
    HttpResponse<String> checkout =
        checkoutPost("chk-paid", shopId, "L-paid", 1, "paid-ho-" + UUID.randomUUID());
    String reservationId = JSON.readTree(checkout.body()).path("reservation_id").asString();
    ObjectNode created =
        orderCreated(externalOrderId, shopId, reservationId, "PREPAID", "L-paid", 1, 3);
    ingest(created);
    assertThat(worker.processAvailable(10)).isEqualTo(1);
    assertThat(countSalesOrders(externalOrderId)).isEqualTo(1);

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
    assertThat(text("SELECT status FROM inbox_event WHERE event_id = ?", paidEventId))
        .isEqualTo("PROCESSED");
  }

  @Test
  void cancelReleasesOrderReservation() throws Exception {
    StockFixture.Shop shop = fixture.shop("ACTIVE");
    String shopId = fixture.tsfShopId(shop);
    UUID account = fixture.tsfChannelAccount(shop, "ACTIVE", "CONNECTED");
    UUID sku = fixture.sku(shop, 5);
    fixture.channelListing(shop, account, "L-cancel", sku, true);

    HttpResponse<String> checkout =
        checkoutPost("chk-cancel", shopId, "L-cancel", 2, "cancel-" + UUID.randomUUID());
    String reservationId = JSON.readTree(checkout.body()).path("reservation_id").asString();
    String externalOrderId = "TSF-CN-" + UUID.randomUUID();
    ingest(orderCreated(externalOrderId, shopId, reservationId, "COD", "L-cancel", 2, 1));
    assertThat(worker.processAvailable(10)).isEqualTo(1);
    assertThat(fixture.reserved(shop, sku)).isEqualTo(2);

    ObjectNode cancelled = orderCancelled(externalOrderId, shopId, 2);
    ingest(cancelled);
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
                        "SELECT count(*) FROM stock_reservation WHERE status = 'ACTIVE'",
                        Long.class)))
        .isZero();
    fixture.assertInvariants(shop);
  }

  private HttpResponse<String> checkoutPost(
      String checkoutId, String shopId, String listingSku, int qty, String idempotencyKey)
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
    HttpRequest request =
        HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + port + "/internal/v1/inventory/reservations"))
            .header("Content-Type", "application/json")
            .header("Idempotency-Key", idempotencyKey)
            .header("Authorization", "Bearer " + checkoutToken())
            .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
            .build();
    return HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  private void ingest(ObjectNode event) throws Exception {
    byte[] body = JSON.writeValueAsBytes(event);
    String eventId = event.path("event_id").asString();
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/internal/v1/events"))
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
    data.put("reservation_id", reservationId);
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

  private static ObjectNode loadExample(String name) throws IOException {
    try (InputStream in =
        MockTsfApplication.class.getResourceAsStream("/contracts/examples/events/" + name)) {
      if (in == null) {
        throw new IllegalStateException("missing example " + name);
      }
      return (ObjectNode) JSON.readTree(in);
    }
  }

  private static boolean matchesStatusChanged(JsonNode received, String externalOrderId) {
    return "order.status_changed".equals(received.path("event_type").asString())
        && externalOrderId.equals(received.path("data").path("order_id").asString())
        && "READY_TO_PICK".equals(received.path("data").path("fulfillment_status").asString());
  }

  private long countSalesOrders(String externalOrderId) throws Exception {
    return count(
        """
        SELECT count(*) FROM sales_order WHERE external_order_id = ?
        """,
        externalOrderId);
  }

  private long outboxCount() throws Exception {
    try (Connection admin = AuthTestSupport.admin();
        var statement = admin.createStatement();
        var rows = statement.executeQuery("SELECT count(*) FROM outbox_event")) {
      assertThat(rows.next()).isTrue();
      return rows.getLong(1);
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

  private static String checkoutToken() {
    return AuthTestSupport.token(
        "tsf-checkout",
        "shop",
        "ACTIVE",
        null,
        1,
        "oms-internal",
        Instant.now().plusSeconds(600),
        java.util.List.of(),
        "SERVICE");
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

  @TestConfiguration
  static class IntakeTestConfig {

    static final AtomicBoolean failAfterOutbox = new AtomicBoolean(false);
    static final AtomicInteger afterOutboxCalls = new AtomicInteger();

    @Bean
    @Primary
    OrderIntakeHooks orderIntakeHooks() {
      return new OrderIntakeHooks() {
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
