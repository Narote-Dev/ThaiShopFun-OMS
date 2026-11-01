package com.thaishopfun.oms.order.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.thaishopfun.mocktsf.MockTsfApplication;
import com.thaishopfun.mocktsf.OmsEndpoint;
import com.thaishopfun.mocktsf.contract.ContractValidator;
import com.thaishopfun.oms.auth.AuthTestSupport;
import com.thaishopfun.oms.catalog.CatalogHttp;
import com.thaishopfun.oms.fulfillment.FulfillmentTestSupport;
import com.thaishopfun.oms.inbox.InboxWorker;
import com.thaishopfun.oms.order.OrderRecipientRepository;
import com.thaishopfun.oms.order.OrderStatusHistoryRepository;
import com.thaishopfun.oms.order.SalesOrder;
import com.thaishopfun.oms.order.SalesOrderRepository;
import com.thaishopfun.oms.stock.ReservationEngine;
import com.thaishopfun.oms.stock.ReserveItem;
import com.thaishopfun.oms.stock.StockOwner;
import com.thaishopfun.oms.tenant.TenantContext;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "oms.outbox.publisher-enabled=false")
class CancelPackedUnpackAcceptanceTest extends OrderIntegrationTest {

  private static final String INBOX_SECRET = "dev-inbox-hmac-secret";
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final ContractValidator CONTRACT = ContractValidator.classpath();
  private static ConfigurableApplicationContext mock;
  private static final HttpClient HTTP =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

  @Autowired SalesOrderRepository orders;
  @Autowired OrderRecipientRepository recipients;
  @Autowired OrderStatusHistoryRepository history;
  @Autowired PlatformTransactionManager transactions;
  @Autowired InboxWorker worker;
  @Autowired JdbcTemplate jdbc;
  @Autowired ReservationEngine stock;

  private OrderFixture fixture;

  @DynamicPropertySource
  static void mockTsf(DynamicPropertyRegistry registry) {
    startMock();
    int mockPort = mockPort();
    registry.add("oms.tsf.base-url", () -> "http://127.0.0.1:" + mockPort);
    registry.add("oms.tsf.token-uri", () -> "http://127.0.0.1:" + mockPort + "/tsf-idp/token");
    registry.add("oms.tsf.client-id", () -> "oms-service");
    registry.add("oms.tsf.client-secret", () -> "dev-oms-service-secret");
    registry.add("oms.tsf.audience", () -> "tsf-internal");
    registry.add("oms.inbox.hmac-secrets", () -> INBOX_SECRET);
    registry.add("oms.inbox.worker-enabled", () -> "false");
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
  void setup() throws Exception {
    mock.getBean(OmsEndpoint.class).setBaseUrl("http://127.0.0.1:" + port);
    fixture = new OrderFixture(orders, recipients, history, transactions);
    try (var admin = AuthTestSupport.admin();
        var statement = admin.createStatement()) {
      statement.execute("TRUNCATE TABLE inbox_event");
    }
  }

  @Test
  void cancelAtPackedClearsPackProgressAndReleasesReservation() throws Exception {
    CatalogHttp.Shop httpShop = http.catalog().shop();
    OrderFixture.Shop shop = OrderFixture.shopFor(httpShop);
    String externalId = "CNX-PACK-" + UUID.randomUUID().toString().substring(0, 8);
    SalesOrder order = fixture.insert(shop, externalId, "READY_TO_PICK", "NONE");
    FulfillmentTestSupport.SkuSeed sku =
        FulfillmentTestSupport.seedSku(shop.tenantId(), "CNX-TEE", "8850000000999");
    FulfillmentTestSupport.addLine(order, sku.skuId(), 2);
    FulfillmentTestSupport.seedInventory(shop.tenantId(), sku.skuId(), sku.warehouseId(), 20);
    ensureOrderHold(shop.tenantId(), order.id(), sku.skuId(), 2);
    packToComplete(httpShop, order.id(), sku.skuCode(), 2);

    assertThat(countPackProgress(shop.tenantId(), order.id())).isPositive();
    assertThat(activePickListMembership(shop.tenantId(), order.id())).isEqualTo(1);
    assertThat(reserved(shop.tenantId(), sku.skuId())).isEqualTo(2);

    ObjectNode cancelled = orderCancelled(externalId, shop.tsfShopId(), 3);
    ingest(cancelled);
    worker.processAvailable(1);
    ingest(cancelled);
    worker.processAvailable(1);

    SalesOrder fresh = orders.findById(order.id()).orElseThrow();
    assertThat(fresh.orderStatus()).isEqualTo("CANCELLED");
    assertThat(countPackProgress(shop.tenantId(), order.id())).isZero();
    assertThat(activePickListMembership(shop.tenantId(), order.id())).isZero();
    assertThat(reserved(shop.tenantId(), sku.skuId())).isZero();
  }

  private void packToComplete(CatalogHttp.Shop httpShop, UUID orderId, String skuCode, int qty)
      throws Exception {
    CatalogHttp.Result pick =
        http.post(
            "/api/v1/pick-lists",
            httpShop.owner(),
            Map.of("order_ids", List.of(orderId.toString())));
    assertThat(pick.status()).isEqualTo(200);
    for (int i = 0; i < qty; i++) {
      CatalogHttp.Result scan =
          http.post(
              "/api/v1/pack/orders/" + orderId + "/scans",
              httpShop.owner(),
              Map.of("scan", skuCode));
      assertThat(scan.status()).isEqualTo(200);
    }
    CatalogHttp.Result done =
        http.post("/api/v1/pack/orders/" + orderId + "/complete", httpShop.owner(), Map.of());
    assertThat(done.status()).isEqualTo(200);
    assertThat(done.body().path("fulfillment_status").asString()).isEqualTo("PACKED");
  }

  private void ensureOrderHold(UUID tenantId, UUID orderId, UUID skuId, int qty) {
    TenantContext.set(tenantId, null);
    try {
      stock.ensureOrderHold(
          StockOwner.order(orderId.toString()),
          List.of(ReserveItem.of(skuId, qty)),
          "hold-" + orderId);
    } finally {
      TenantContext.clear();
    }
  }

  private int reserved(UUID tenantId, UUID skuId) {
    TenantContext.set(tenantId, null);
    try {
      Integer value =
          jdbc.queryForObject(
              """
              SELECT reserved FROM inventory
              WHERE tenant_id = ? AND sku_id = ?
              LIMIT 1
              """,
              Integer.class,
              tenantId,
              skuId);
      return value == null ? 0 : value;
    } finally {
      TenantContext.clear();
    }
  }

  private long countPackProgress(UUID tenantId, UUID orderId) {
    TenantContext.set(tenantId, null);
    try {
      Long count =
          jdbc.queryForObject(
              "SELECT count(*) FROM order_pack_progress WHERE tenant_id = ? AND order_id = ?",
              Long.class,
              tenantId,
              orderId);
      return count == null ? 0 : count;
    } finally {
      TenantContext.clear();
    }
  }

  private long activePickListMembership(UUID tenantId, UUID orderId) {
    TenantContext.set(tenantId, null);
    try {
      Long count =
          jdbc.queryForObject(
              """
              SELECT count(*) FROM pick_list_order
              WHERE tenant_id = ? AND order_id = ? AND released_at IS NULL
              """,
              Long.class,
              tenantId,
              orderId);
      return count == null ? 0 : count;
    } finally {
      TenantContext.clear();
    }
  }

  private ObjectNode orderCancelled(String externalOrderId, String shopId, long version)
      throws IOException {
    ObjectNode event = loadExample("order.cancelled.json");
    event.put("event_id", "oc-" + externalOrderId);
    event.put("tsf_shop_id", shopId);
    event.put("aggregate_id", externalOrderId);
    event.put("aggregate_version", version);
    ((ObjectNode) event.get("data")).put("order_id", externalOrderId);
    assertThat(CONTRACT.envelopeErrors(JSON.writeValueAsString(event))).isEmpty();
    return event;
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
    assertThat(HTTP.send(request, HttpResponse.BodyHandlers.ofString()).statusCode())
        .isEqualTo(202);
  }

  private static ObjectNode loadExample(String name) throws IOException {
    try (InputStream in =
        MockTsfApplication.class.getResourceAsStream("/contracts/examples/events/" + name)) {
      return (ObjectNode) JSON.readTree(in);
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
    return Integer.parseInt(mock.getEnvironment().getProperty("local.server.port"));
  }
}
