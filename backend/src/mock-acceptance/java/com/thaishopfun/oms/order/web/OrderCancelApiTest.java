package com.thaishopfun.oms.order.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.thaishopfun.mocktsf.MockTsfApplication;
import com.thaishopfun.mocktsf.OmsEndpoint;
import com.thaishopfun.mocktsf.contract.ContractValidator;
import com.thaishopfun.oms.auth.AuthTestSupport;
import com.thaishopfun.oms.catalog.CatalogHttp;
import com.thaishopfun.oms.inbox.InboxWorker;
import com.thaishopfun.oms.invariant.PiiLogAssertions;
import com.thaishopfun.oms.order.OrderRecipientRepository;
import com.thaishopfun.oms.order.OrderStatusHistoryRepository;
import com.thaishopfun.oms.order.SalesOrder;
import com.thaishopfun.oms.order.SalesOrderRepository;
import com.thaishopfun.oms.order.demo.OrderDemoCatalogService;
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
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

@ActiveProfiles("test")
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "oms.outbox.publisher-enabled=false")
class OrderCancelApiTest extends OrderIntegrationTest {

  private static final String INBOX_SECRET = "dev-inbox-hmac-secret";
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final ContractValidator CONTRACT = ContractValidator.classpath();
  private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(20);

  private static ConfigurableApplicationContext mock;

  @Autowired SalesOrderRepository orders;
  @Autowired InboxWorker worker;
  @Autowired OrderRecipientRepository recipients;
  @Autowired OrderStatusHistoryRepository history;
  @Autowired PlatformTransactionManager transactions;
  @Autowired JdbcTemplate jdbc;
  @Autowired OrderDemoCatalogService demoCatalog;
  private OrderFixture fixture;
  private static final HttpClient HTTP =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

  @DynamicPropertySource
  static void mockTsf(DynamicPropertyRegistry registry) {
    startMock();
    int port = mockPort();
    registry.add("oms.tsf.base-url", () -> "http://127.0.0.1:" + port);
    registry.add("oms.tsf.token-uri", () -> "http://127.0.0.1:" + port + "/tsf-idp/token");
    registry.add("oms.tsf.client-id", () -> "oms-service");
    registry.add("oms.tsf.client-secret", () -> "dev-oms-service-secret");
    registry.add("oms.tsf.audience", () -> "tsf-internal");
    registry.add("oms.channel.tsf.retry-max-attempts", () -> "1");
    registry.add("oms.channel.tsf.retry-wait-base", () -> "10ms");
    registry.add("oms.channel.tsf.retry-wait-max", () -> "20ms");
    registry.add("oms.inbox.hmac-secrets", () -> INBOX_SECRET);
    registry.add("oms.inbox.worker-enabled", () -> "true");
    registry.add("oms.inbox.jitter-ratio", () -> "0");
    registry.add("oms.security.internal-client-ids", () -> "tsf,tsf-checkout");
  }

  @AfterAll
  static void stopMock() {
    if (mock != null) {
      mock.close();
    }
  }

  @BeforeEach
  void fixture() {
    mock.getBean(OmsEndpoint.class).setBaseUrl("http://127.0.0.1:" + port);
    fixture = new OrderFixture(orders, recipients, history, transactions);
  }

  private void registerMockOrder(String externalOrderId) throws Exception {
    HttpResponse<String> response =
        HTTP.send(
            HttpRequest.newBuilder(
                    URI.create(
                        "http://127.0.0.1:"
                            + mockPort()
                            + "/control/rest/demo-order/"
                            + externalOrderId))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    assertThat(response.statusCode()).isEqualTo(200);
  }

  @Test
  void ownerCancelIsIdempotent() throws Exception {
    try (PiiLogAssertions logs = PiiLogAssertions.attach("com.thaishopfun.oms.order.web")) {
      ownerCancelIsIdempotentBody();
      logs.assertNoPii();
    }
  }

  private void ownerCancelIsIdempotentBody() throws Exception {
    ActiveShop shop = shopActive();
    registerMockOrder("TSF-240929-000123");
    SalesOrder order = fixture.insert(shop.fixture(), "TSF-240929-000123", "READY_TO_PICK", "NONE");
    clearCancelHits(order.externalOrderId());
    long historyBefore = historyCount(order.id());
    CatalogHttp.Result first =
        http.post(
            OrderHttp.ordersPath("/" + order.id() + "/cancel-requests"),
            shop.owner(),
            Map.of("reason", "buyer asked"));
    assertThat(first.status()).isEqualTo(202);
    assertThat(first.body().path("status").asString()).isEqualTo("PENDING");
    long historyRows = historyCount(order.id());
    long auditRows = auditCount(order.id());
    assertThat(historyRows).isEqualTo(historyBefore + 1);
    assertThat(auditRows).isEqualTo(1);
    assertThat(holdReason(order.id())).isEqualTo("CHANNEL_CANCEL_PENDING");
    assertThat(cancelHits(order.externalOrderId())).hasSize(1);
    assertThat(idempotencyKey(cancelHits(order.externalOrderId()).get(0)))
        .isEqualTo("cancel-request:" + order.id());

    CatalogHttp.Result second =
        http.post(
            OrderHttp.ordersPath("/" + order.id() + "/cancel-requests"),
            shop.owner(),
            Map.of("reason", "buyer asked"));
    assertThat(second.status()).isEqualTo(202);
    assertThat(historyCount(order.id())).isEqualTo(historyRows);
    assertThat(auditCount(order.id())).isEqualTo(auditRows);
    assertThat(cancelHits(order.externalOrderId())).hasSize(1);
  }

  @Test
  void staffForbidden() throws Exception {
    ActiveShop shop = shopActive();
    registerMockOrder("TSF-240929-000132");
    SalesOrder order = fixture.insert(shop.fixture(), "TSF-240929-000132", "READY_TO_PICK", "NONE");
    CatalogHttp.Result result =
        http.post(
            OrderHttp.ordersPath("/" + order.id() + "/cancel-requests"),
            shop.staff(),
            Map.of("reason", "nope"));
    assertThat(result.status()).isEqualTo(403);
    assertThat(result.error()).isEqualTo("FORBIDDEN");
  }

  @Test
  void blankCancelReasonRejectedBeforeChannel() throws Exception {
    ActiveShop shop = shopActive();
    registerMockOrder("TSF-240929-000131");
    SalesOrder order = fixture.insert(shop.fixture(), "TSF-240929-000131", "READY_TO_PICK", "NONE");
    clearCancelHits(order.externalOrderId());
    CatalogHttp.Result result =
        http.post(
            OrderHttp.ordersPath("/" + order.id() + "/cancel-requests"),
            shop.owner(),
            Map.of("reason", "   "));
    assertThat(result.status()).isEqualTo(400);
    assertThat(result.error()).isEqualTo("VALIDATION_FAILED");
    assertThat(result.body().path("errors").get(0).path("field").asString()).isEqualTo("reason");
    assertThat(holdReason(order.id())).isEqualTo("NONE");
    assertThat(cancelHits(order.externalOrderId())).isEmpty();
    assertThat(auditCount(order.id())).isZero();
  }

  @Test
  void missingCancelBodyRejectedBeforeChannel() throws Exception {
    ActiveShop shop = shopActive();
    registerMockOrder("TSF-240929-000133");
    SalesOrder order = fixture.insert(shop.fixture(), "TSF-240929-000133", "READY_TO_PICK", "NONE");
    clearCancelHits(order.externalOrderId());
    CatalogHttp.Result result =
        http.post(OrderHttp.ordersPath("/" + order.id() + "/cancel-requests"), shop.owner(), null);
    assertThat(result.status()).isEqualTo(400);
    assertThat(result.error()).isEqualTo("VALIDATION_FAILED");
    assertThat(holdReason(order.id())).isEqualTo("NONE");
    assertThat(cancelHits(order.externalOrderId())).isEmpty();
    assertThat(auditCount(order.id())).isZero();
  }

  @Test
  void channelIdempotencyConflictReturns409WithoutHold() throws Exception {
    ActiveShop shop = shopActive();
    String external = "TSF-240929-000134";
    registerMockOrder(external);
    SalesOrder order = fixture.insert(shop.fixture(), external, "READY_TO_PICK", "NONE");
    clearCancelHits(external);
    String idempotencyKey = "cancel-request:" + order.id();
    primeMockCancel(external, idempotencyKey, "already sent");
    assertThat(cancelHits(external)).hasSize(1);
    CatalogHttp.Result result =
        http.post(
            OrderHttp.ordersPath("/" + order.id() + "/cancel-requests"),
            shop.owner(),
            Map.of("reason", "different reason"));
    assertThat(result.status()).isEqualTo(409);
    assertThat(result.error()).isEqualTo("CANCEL_REQUEST_CONFLICT");
    assertThat(holdReason(order.id())).isEqualTo("NONE");
    assertThat(auditCount(order.id())).isZero();
  }

  @Test
  void graceEntitlementBlocksWrite() throws Exception {
    ActiveShop shop = shopActiveGrace();
    registerMockOrder("TSF-240929-000125");
    SalesOrder order = fixture.insert(shop.fixture(), "TSF-240929-000125", "READY_TO_PICK", "NONE");
    CatalogHttp.Result result =
        http.post(
            OrderHttp.ordersPath("/" + order.id() + "/cancel-requests"),
            shop.owner(),
            Map.of("reason", "nope"));
    assertThat(result.status()).isEqualTo(403);
    assertThat(result.error()).isEqualTo("ENTITLEMENT_GRACE");
  }

  @Test
  void shippedOrderConflict() throws Exception {
    ActiveShop shop = shopActive();
    registerMockOrder("TSF-240929-000126");
    SalesOrder order = fixture.insert(shop.fixture(), "TSF-240929-000126", "SHIPPED", "NONE");
    clearCancelHits(order.externalOrderId());
    CatalogHttp.Result result =
        http.post(
            OrderHttp.ordersPath("/" + order.id() + "/cancel-requests"),
            shop.owner(),
            Map.of("reason", "late"));
    assertThat(result.status()).isEqualTo(409);
    assertThat(result.error()).isEqualTo("ORDER_NOT_CANCELLABLE");
    assertThat(holdReason(order.id())).isEqualTo("NONE");
    assertThat(cancelHits(order.externalOrderId())).isEmpty();
  }

  @Test
  void cancelledOrderConflictWithoutTsfCall() throws Exception {
    ActiveShop shop = shopActive();
    registerMockOrder("TSF-240929-000127");
    SalesOrder order =
        fixture.insert(
            shop.fixture(),
            "TSF-240929-000127",
            "CANCELLED",
            "PAID",
            "UNFULFILLED",
            "NONE",
            shop.fixture().channelAccountId());
    clearCancelHits(order.externalOrderId());
    CatalogHttp.Result result =
        http.post(
            OrderHttp.ordersPath("/" + order.id() + "/cancel-requests"),
            shop.owner(),
            Map.of("reason", "late"));
    assertThat(result.status()).isEqualTo(409);
    assertThat(cancelHits(order.externalOrderId())).isEmpty();
  }

  @Test
  void channelFaultLeavesOrderUnchanged() throws Exception {
    ActiveShop shop = shopActive();
    registerMockOrder("TSF-240929-000128");
    SalesOrder order = fixture.insert(shop.fixture(), "TSF-240929-000128", "READY_TO_PICK", "NONE");
    armFault("POST", "/internal/v1/orders/TSF-240929-000128/cancel-requests", 503, 1);
    CatalogHttp.Result result =
        http.post(
            OrderHttp.ordersPath("/" + order.id() + "/cancel-requests"),
            shop.owner(),
            Map.of("reason", "fault"));
    assertThat(result.status()).isEqualTo(503);
    assertThat(holdReason(order.id())).isEqualTo("NONE");
    assertThat(auditCount(order.id())).isZero();
  }

  @Test
  void channelRateLimitLeavesOrderUnchanged() throws Exception {
    ActiveShop shop = shopActive();
    registerMockOrder("TSF-240929-000129");
    SalesOrder order = fixture.insert(shop.fixture(), "TSF-240929-000129", "READY_TO_PICK", "NONE");
    armFault("POST", "/internal/v1/orders/TSF-240929-000129/cancel-requests", 429, 1);
    CatalogHttp.Result result =
        http.post(
            OrderHttp.ordersPath("/" + order.id() + "/cancel-requests"),
            shop.owner(),
            Map.of("reason", "rate"));
    assertThat(result.status()).isEqualTo(429);
    assertThat(holdReason(order.id())).isEqualTo("NONE");
    assertThat(cancelHits(order.externalOrderId())).isEmpty();
  }

  @Test
  void demoOrdersSeedProducesExpectedStates() throws Exception {
    String owner =
        CatalogHttp.token("owner-demo-" + UUID.randomUUID(), "shop_active", "OWNER", "ACTIVE");
    assertThat(http.get("/api/v1/me", owner).status()).isEqualTo(200);
    TenantContext.clear();
    assertThat(demoCatalog.ensureDemoCatalog().get("status")).isEqualTo("OK");

    String shop = "shop_active";
    ingest(
        orderCreated(
            "DEMO-READY",
            shop,
            "a0000001-0001-4001-8001-000000000001",
            "PREPAID",
            "L-demo-ready",
            1));
    ingest(
        orderCreated(
            "DEMO-COD", shop, "a0000002-0002-4002-8002-000000000002", "COD", "L-demo-cod", 2));
    ingest(
        orderCreated(
            "DEMO-OOS", shop, "a0000003-0003-4003-8003-000000000003", "COD", "L-demo-oos", 3));
    ingest(
        orderCreated(
            "DEMO-UNMAPPED",
            shop,
            "a0000004-0004-4004-8004-000000000004",
            "COD",
            "L-demo-missing",
            4));
    ingest(
        orderCreated(
            "DEMO-BUNDLE",
            shop,
            "a0000005-0005-4005-8005-000000000005",
            "COD",
            "L-demo-bundle",
            5));
    ingest(orderPaid("DEMO-READY", shop, 6));
    ingest(
        orderCreated(
            "DEMO-CANCELLED",
            shop,
            "a0000006-0006-4006-8006-000000000006",
            "COD",
            "L-demo-cancel",
            7));
    ingest(orderCancelled("DEMO-CANCELLED", shop, 8));

    int processed;
    int rounds = 0;
    do {
      processed = worker.processAvailable(20);
      rounds++;
    } while (processed > 0 && rounds < 50);
    assertThat(rounds).isLessThan(50);

    assertDemoOrder("DEMO-READY", "READY_TO_PICK", "PAID", "NONE", null);
    assertDemoOrder("DEMO-COD", "READY_TO_PICK", "COD_PENDING", "NONE", null);
    assertDemoOrder("DEMO-OOS", "UNFULFILLED", "COD_PENDING", "OUT_OF_STOCK", null);
    assertDemoOrder("DEMO-UNMAPPED", "UNFULFILLED", "COD_PENDING", "SKU_NOT_MAPPED", null);
    assertDemoOrder(
        "DEMO-BUNDLE", "UNFULFILLED", "COD_PENDING", "OUT_OF_STOCK", "bundle has no components");
    assertDemoOrderStatus("DEMO-CANCELLED", "CANCELLED");
  }

  @Test
  void orderCancelledIngestSetsCancelled() throws Exception {
    ActiveShop shop = shopActive();
    registerMockOrder("TSF-240929-000130");
    SalesOrder order = fixture.insert(shop.fixture(), "TSF-240929-000130", "READY_TO_PICK", "NONE");
    CatalogHttp.Result cancel =
        http.post(
            OrderHttp.ordersPath("/" + order.id() + "/cancel-requests"),
            shop.owner(),
            Map.of("reason", "buyer"));
    assertThat(cancel.status()).isEqualTo(202);
    assertThat(holdReason(order.id())).isEqualTo("CHANNEL_CANCEL_PENDING");
    ingest(orderCancelled(order.externalOrderId(), shop.fixture().tsfShopId(), 1));
    assertThat(worker.processAvailable(10)).isEqualTo(1);
    assertThat(orderStatus(order.id())).isEqualTo("CANCELLED");
  }

  @Test
  void usesChannelAccountExternalShopNotTenantPrimary() throws Exception {
    ActiveShop shop = shopActive();
    UUID altChannel =
        OrderFixture.ensureChannelAccount(shop.fixture().tenantId(), "shop_alt_active");
    String external = "TSF-ALT-" + UUID.randomUUID().toString().substring(0, 8);
    registerMockOrder(external);
    SalesOrder order =
        fixture.insert(
            shop.fixture(), external, "ACTIVE", "PAID", "READY_TO_PICK", "NONE", altChannel);
    clearCancelHits(order.externalOrderId());
    CatalogHttp.Result result =
        http.post(
            OrderHttp.ordersPath("/" + order.id() + "/cancel-requests"),
            shop.owner(),
            Map.of("reason", "alt shop"));
    assertThat(result.status()).isEqualTo(202);
    assertThat(cancelHits(order.externalOrderId())).hasSize(1);
    try (Connection admin = AuthTestSupport.admin();
        PreparedStatement ps =
            admin.prepareStatement("SELECT external_shop_id FROM channel_account WHERE id = ?")) {
      ps.setObject(1, altChannel);
      try (var rs = ps.executeQuery()) {
        assertThat(rs.next()).isTrue();
        assertThat(rs.getString(1)).isEqualTo("shop_alt_active");
      }
    }
  }

  private ActiveShop shopActive() throws Exception {
    CatalogHttp.Shop httpShop = http.catalog().shop();
    OrderFixture.Shop shop = OrderFixture.shopFor(httpShop);
    String staff =
        CatalogHttp.token("staff-" + UUID.randomUUID(), httpShop.shopId(), "STAFF", "ACTIVE");
    assertThat(http.get("/api/v1/me", staff).status()).isEqualTo(200);
    return new ActiveShop(shop, httpShop.owner(), staff);
  }

  private ActiveShop shopActiveGrace() throws Exception {
    String shopId = "shop-grace-" + UUID.randomUUID();
    String owner = CatalogHttp.token("owner-" + UUID.randomUUID(), shopId, "OWNER", "GRACE");
    CatalogHttp.Result me = http.get("/api/v1/me", owner);
    assertThat(me.status()).isEqualTo(200);
    UUID tenantId = UUID.fromString(me.body().path("tenant").path("id").asString());
    OrderFixture.Shop base = OrderFixture.shopFor(new CatalogHttp.Shop(shopId, owner, tenantId));
    return new ActiveShop(base, owner, owner);
  }

  private record ActiveShop(OrderFixture.Shop fixture, String owner, String staff) {}

  private String holdReason(UUID orderId) throws Exception {
    try (Connection admin = AuthTestSupport.admin();
        PreparedStatement ps =
            admin.prepareStatement("SELECT hold_reason FROM sales_order WHERE id = ?")) {
      ps.setObject(1, orderId);
      try (var rs = ps.executeQuery()) {
        rs.next();
        return rs.getString(1);
      }
    }
  }

  private UUID tenantIdFor(UUID orderId) throws Exception {
    try (Connection admin = AuthTestSupport.admin();
        PreparedStatement ps =
            admin.prepareStatement("SELECT tenant_id FROM sales_order WHERE id = ?")) {
      ps.setObject(1, orderId);
      try (var rs = ps.executeQuery()) {
        if (!rs.next()) {
          throw new IllegalStateException("order not found: " + orderId);
        }
        return rs.getObject("tenant_id", UUID.class);
      }
    }
  }

  private long historyCount(UUID orderId) throws Exception {
    try (Connection admin = AuthTestSupport.admin();
        PreparedStatement ps =
            admin.prepareStatement(
                "SELECT count(*) FROM order_status_history WHERE order_id = ?")) {
      ps.setObject(1, orderId);
      try (var rs = ps.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }

  private long auditCount(UUID orderId) throws Exception {
    try (Connection admin = AuthTestSupport.admin();
        PreparedStatement ps =
            admin.prepareStatement(
                "SELECT count(*) FROM audit_log WHERE entity_type = 'sales_order' AND entity_id = ?")) {
      ps.setObject(1, orderId.toString());
      try (var rs = ps.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }

  private String orderStatus(UUID orderId) throws Exception {
    try (Connection admin = AuthTestSupport.admin();
        PreparedStatement ps =
            admin.prepareStatement("SELECT order_status FROM sales_order WHERE id = ?")) {
      ps.setObject(1, orderId);
      try (var rs = ps.executeQuery()) {
        rs.next();
        return rs.getString(1);
      }
    }
  }

  private tools.jackson.databind.JsonNode cancelHits(String externalOrderId) throws Exception {
    HttpResponse<String> response =
        HTTP.send(
            HttpRequest.newBuilder(
                    URI.create(
                        "http://127.0.0.1:"
                            + mockPort()
                            + "/control/rest/cancel-hits/"
                            + externalOrderId))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    assertThat(response.statusCode()).isEqualTo(200);
    return new tools.jackson.databind.json.JsonMapper().readTree(response.body()).path("hits");
  }

  private void clearCancelHits(String externalOrderId) throws Exception {
    HTTP.send(
        HttpRequest.newBuilder(
                URI.create(
                    "http://127.0.0.1:"
                        + mockPort()
                        + "/control/rest/cancel-hits/"
                        + externalOrderId))
            .DELETE()
            .build(),
        HttpResponse.BodyHandlers.discarding());
  }

  private static String idempotencyKey(tools.jackson.databind.JsonNode hit) {
    if (hit.hasNonNull("idempotencyKey")) {
      return hit.path("idempotencyKey").asString();
    }
    return hit.path("idempotency_key").asString();
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
      long aggregateVersion)
      throws IOException {
    ObjectNode event = loadExample("order.created.json");
    event.put("event_id", "demo-" + orderId);
    event.put("tsf_shop_id", shopId);
    event.put("aggregate_id", orderId);
    event.put("aggregate_version", aggregateVersion);
    event.put("occurred_at", Instant.now().truncatedTo(ChronoUnit.SECONDS).toString());
    ObjectNode data = (ObjectNode) event.get("data");
    data.put("order_id", orderId);
    data.put("reservation_id", reservationId);
    data.put("payment_method", paymentMethod);
    ArrayNode lines = JSON.createArrayNode();
    ObjectNode line = JSON.createObjectNode();
    line.put("line_id", "L1");
    line.put("listing_sku_id", listingSku);
    line.put("seller_sku", "SKU-DEMO");
    line.put("name", "Demo item");
    line.put("qty", 1);
    line.put("unit_price", 100);
    lines.add(line);
    data.set("lines", lines);
    assertThat(CONTRACT.envelopeErrors(JSON.writeValueAsString(event))).isEmpty();
    return event;
  }

  private ObjectNode orderPaid(String orderId, String shopId, long aggregateVersion)
      throws IOException {
    ObjectNode event = loadExample("order.paid.json");
    event.put("event_id", "demo-paid-" + orderId);
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

  private void assertDemoOrderStatus(String externalId, String orderStatus) throws Exception {
    try (Connection admin = AuthTestSupport.admin();
        PreparedStatement ps =
            admin.prepareStatement(
                "SELECT order_status FROM sales_order WHERE external_order_id = ?")) {
      ps.setString(1, externalId);
      try (var rs = ps.executeQuery()) {
        assertThat(rs.next()).as("order %s", externalId).isTrue();
        assertThat(rs.getString("order_status")).isEqualTo(orderStatus);
      }
    }
  }

  private void assertDemoOrder(
      String externalId, String fulfillment, String payment, String hold, String holdNote)
      throws Exception {
    try (Connection admin = AuthTestSupport.admin();
        PreparedStatement ps =
            admin.prepareStatement(
                """
                SELECT fulfillment_status, payment_status, hold_reason, hold_note
                FROM sales_order WHERE external_order_id = ?
                """)) {
      ps.setString(1, externalId);
      try (var rs = ps.executeQuery()) {
        assertThat(rs.next()).as("order %s", externalId).isTrue();
        assertThat(rs.getString("fulfillment_status")).isEqualTo(fulfillment);
        assertThat(rs.getString("payment_status")).isEqualTo(payment);
        assertThat(rs.getString("hold_reason")).isEqualTo(hold);
        if (holdNote == null) {
          assertThat(rs.getString("hold_note")).isNull();
        } else {
          assertThat(rs.getString("hold_note")).isEqualTo(holdNote);
        }
      }
    }
  }

  private static String tsfToken() {
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

  private void primeMockCancel(String externalOrderId, String idempotencyKey, String reason)
      throws Exception {
    String body = JSON.createObjectNode().put("reason", reason).toString();
    HttpResponse<String> response =
        HTTP.send(
            HttpRequest.newBuilder(
                    URI.create(
                        "http://127.0.0.1:"
                            + mockPort()
                            + "/internal/v1/orders/"
                            + externalOrderId
                            + "/cancel-requests"))
                .timeout(HTTP_TIMEOUT)
                .header("Authorization", "Bearer " + mockServiceToken())
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", idempotencyKey)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    assertThat(response.statusCode()).isEqualTo(202);
  }

  private static String mockServiceToken() throws Exception {
    HttpResponse<String> token =
        HTTP.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + mockPort() + "/tsf-idp/token"))
                .timeout(HTTP_TIMEOUT)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        "grant_type=client_credentials&client_id=oms-service&client_secret=dev-oms-service-secret",
                        StandardCharsets.UTF_8))
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    assertThat(token.statusCode()).isEqualTo(200);
    return JSON.readTree(token.body()).path("access_token").asString();
  }

  private static void armFault(String method, String path, int status, int times) throws Exception {
    HttpResponse<String> response =
        HTTP.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + mockPort() + "/control/faults"))
                .header("Content-Type", "application/json")
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        """
                        {"method":"%s","path":"%s","status":%d,"times":%d%s}
                        """
                            .formatted(
                                method,
                                path,
                                status,
                                times,
                                status == 429 ? ",\"retry_after\":30" : ""),
                        StandardCharsets.UTF_8))
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    assertThat(response.statusCode()).isEqualTo(200);
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
            "--mock.issuer=http://mock-cancel.test/tsf-idp",
            "--spring.main.banner-mode=off",
            "--spring.main.register-shutdown-hook=false");
  }

  private static int mockPort() {
    String port = mock.getEnvironment().getProperty("local.server.port");
    return Integer.parseInt(port);
  }
}
