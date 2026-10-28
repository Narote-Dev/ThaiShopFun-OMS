package com.thaishopfun.oms.order.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.thaishopfun.mocktsf.MockTsfApplication;
import com.thaishopfun.mocktsf.OmsEndpoint;
import com.thaishopfun.oms.catalog.CatalogHttp;
import com.thaishopfun.oms.fulfillment.FulfillmentTestSupport;
import com.thaishopfun.oms.fulfillment.LabelCachePurgeJob;
import com.thaishopfun.oms.order.OrderRecipientRepository;
import com.thaishopfun.oms.order.OrderStatusHistoryRepository;
import com.thaishopfun.oms.order.SalesOrder;
import com.thaishopfun.oms.order.SalesOrderRepository;
import com.thaishopfun.oms.stock.ReservationEngine;
import com.thaishopfun.oms.stock.ReserveItem;
import com.thaishopfun.oms.stock.StockOwner;
import com.thaishopfun.oms.tenant.TenantContext;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class ShipLabelApiTest extends OrderIntegrationTest {

  private static ConfigurableApplicationContext mock;
  private static final HttpClient HTTP = HttpClient.newBuilder().build();

  @Autowired SalesOrderRepository orders;
  @Autowired OrderRecipientRepository recipients;
  @Autowired OrderStatusHistoryRepository history;
  @Autowired PlatformTransactionManager transactions;
  @Autowired ReservationEngine stock;
  @Autowired JdbcTemplate jdbc;
  @Autowired LabelCachePurgeJob labelPurge;

  private OrderFixture fixture;

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
  }

  @AfterAll
  static void stopMock() {
    if (mock != null) {
      mock.close();
    }
  }

  @BeforeEach
  void setup() {
    mock.getBean(OmsEndpoint.class).setBaseUrl("http://127.0.0.1:" + port);
    fixture = new OrderFixture(orders, recipients, history, transactions);
  }

  @Test
  void labelBatchShipAndPurge() throws Exception {
    CatalogHttp.Shop httpShop = http.catalog().shop();
    OrderFixture.Shop shop = OrderFixture.shopFor(httpShop);
    String externalId = "LBL-" + UUID.randomUUID().toString().substring(0, 8);
    registerMockOrder(externalId);
    SalesOrder order = fixture.insert(shop, externalId, "READY_TO_PICK", "NONE");
    FulfillmentTestSupport.SkuSeed sku =
        FulfillmentTestSupport.seedSku(shop.tenantId(), "SHIP-TEE", "8850000000100");
    FulfillmentTestSupport.addLine(order, sku.skuId(), 2);
    FulfillmentTestSupport.seedInventory(shop.tenantId(), sku.skuId(), sku.warehouseId(), 20);
    packToComplete(httpShop, order.id(), sku.skuCode(), 2);
    assertPacked(httpShop, order.id());

    byte[] firstPdf =
        http.postPdf(
            "/api/v1/ship/labels",
            httpShop.owner(),
            Map.of("order_ids", List.of(order.id().toString())));
    assertThat(new String(firstPdf, 0, 4, StandardCharsets.US_ASCII)).isEqualTo("%PDF");

    byte[] replay =
        http.postPdf(
            "/api/v1/ship/labels",
            httpShop.owner(),
            Map.of("order_ids", List.of(order.id().toString())));
    assertThat(replay).isEqualTo(firstPdf);

    ensureOrderHold(shop.tenantId(), order.id(), sku.skuId(), 2);
    int onHandBefore = onHand(shop.tenantId(), sku.skuId());
    CatalogHttp.Result ship =
        http.post("/api/v1/ship/orders/" + order.id() + "/confirm", httpShop.owner(), Map.of());
    assertThat(ship.status()).isEqualTo(200);
    assertThat(ship.body().path("fulfillment_status").asString()).isEqualTo("SHIPPED");
    assertThat(onHand(shop.tenantId(), sku.skuId())).isEqualTo(onHandBefore - 2);

    CatalogHttp.Result shipAgain =
        http.post("/api/v1/ship/orders/" + order.id() + "/confirm", httpShop.owner(), Map.of());
    assertThat(shipAgain.status()).isEqualTo(200);
    assertThat(onHand(shop.tenantId(), sku.skuId())).isEqualTo(onHandBefore - 2);

    expireLabelCache(shop.tenantId(), order.id());
    assertThat(labelPurge.runOnce()).isEqualTo(1);
    assertThat(countLabelCache(shop.tenantId(), order.id())).isZero();
  }

  @Test
  void manualTrackingWhenLabelUnavailable() throws Exception {
    CatalogHttp.Shop httpShop = http.catalog().shop();
    OrderFixture.Shop shop = OrderFixture.shopFor(httpShop);
    String externalId = "NO-LBL-" + UUID.randomUUID().toString().substring(0, 8);
    registerMockOrder(externalId);
    SalesOrder order = fixture.insert(shop, externalId, "READY_TO_PICK", "NONE");
    FulfillmentTestSupport.SkuSeed sku =
        FulfillmentTestSupport.seedSku(shop.tenantId(), "MAN-TEE", "8850000000101");
    FulfillmentTestSupport.addLine(order, sku.skuId(), 1);
    FulfillmentTestSupport.seedInventory(shop.tenantId(), sku.skuId(), sku.warehouseId(), 5);
    packToComplete(httpShop, order.id(), sku.skuCode(), 1);
    assertPacked(httpShop, order.id());

    CatalogHttp.Result labels =
        http.post(
            "/api/v1/ship/labels",
            httpShop.owner(),
            Map.of("order_ids", List.of(order.id().toString())));
    assertThat(labels.status()).isEqualTo(422);
    assertThat(labels.body().path("error").asString()).isEqualTo("LABEL_NOT_AVAILABLE");

    CatalogHttp.Result manual =
        http.post(
            "/api/v1/ship/orders/" + order.id() + "/manual-tracking",
            httpShop.owner(),
            Map.of("carrier", "KERRY", "tracking_no", "K123456789TH"));
    assertThat(manual.status()).isEqualTo(200);
    assertThat(manual.body().path("shipment.tracking_no").asString()).isEqualTo("K123456789TH");

    byte[] slip =
        http.postPdf(
            "/api/v1/ship/labels",
            httpShop.owner(),
            Map.of("order_ids", List.of(order.id().toString())));
    assertThat(new String(slip, 0, 4, StandardCharsets.US_ASCII)).isEqualTo("%PDF");
  }

  private void packToComplete(CatalogHttp.Shop httpShop, UUID orderId, String skuCode, int qty)
      throws Exception {
    CatalogHttp.Result pick =
        http.post(
            "/api/v1/pick-lists",
            httpShop.owner(),
            Map.of("order_ids", List.of(orderId.toString())));
    assertThat(pick.status()).as(pick.raw()).isEqualTo(200);
    for (int i = 0; i < qty; i++) {
      CatalogHttp.Result scan =
          http.post(
              "/api/v1/pack/orders/" + orderId + "/scans",
              httpShop.owner(),
              Map.of("scan", skuCode));
      assertThat(scan.status()).as(scan.raw()).isEqualTo(200);
    }
    CatalogHttp.Result done =
        http.post("/api/v1/pack/orders/" + orderId + "/complete", httpShop.owner(), Map.of());
    assertThat(done.status()).as(done.raw()).isEqualTo(200);
    assertThat(done.body().path("fulfillment_status").asString()).isEqualTo("PACKED");
  }

  private void assertPacked(CatalogHttp.Shop httpShop, UUID orderId) {
    CatalogHttp.Result view = http.get(OrderHttp.ordersPath("/" + orderId), httpShop.owner());
    assertThat(view.status()).as(view.raw()).isEqualTo(200);
    assertThat(view.body().path("fulfillment_status").asString()).isEqualTo("PACKED");
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

  private static void startMock() {
    if (mock != null) {
      return;
    }
    SpringApplication app = MockTsfApplication.application();
    mock =
        app.run(
            "--server.port=0",
            "--server.address=127.0.0.1",
            "--mock.issuer=http://mock-ship.test/tsf-idp",
            "--spring.main.banner-mode=off",
            "--spring.main.register-shutdown-hook=false");
  }

  private static int mockPort() {
    return Integer.parseInt(mock.getEnvironment().getProperty("local.server.port"));
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

  private int onHand(UUID tenantId, UUID skuId) {
    TransactionTemplate read = new TransactionTemplate(transactions);
    read.setReadOnly(true);
    TenantContext.set(tenantId, null);
    try {
      return read.execute(
          status -> {
            Integer value =
                jdbc.queryForObject(
                    """
                    SELECT on_hand FROM inventory
                    WHERE tenant_id = ? AND sku_id = ?
                    LIMIT 1
                    """,
                    Integer.class,
                    tenantId,
                    skuId);
            return value == null ? 0 : value;
          });
    } finally {
      TenantContext.clear();
    }
  }

  private void expireLabelCache(UUID tenantId, UUID orderId) {
    TransactionTemplate write = new TransactionTemplate(transactions);
    TenantContext.set(tenantId, null);
    try {
      write.executeWithoutResult(
          status ->
              jdbc.update(
                  """
                  UPDATE shipment SET label_cached_until = ?
                  WHERE tenant_id = ? AND order_id = ?
                  """,
                  Timestamp.from(Instant.parse("2020-01-01T00:00:00Z")),
                  tenantId,
                  orderId));
    } finally {
      TenantContext.clear();
    }
  }

  private int countLabelCache(UUID tenantId, UUID orderId) {
    TransactionTemplate read = new TransactionTemplate(transactions);
    read.setReadOnly(true);
    TenantContext.set(tenantId, null);
    try {
      return read.execute(
          status -> {
            Integer count =
                jdbc.queryForObject(
                    """
                    SELECT count(*) FROM shipment_label_cache slc
                    JOIN shipment s ON s.id = slc.shipment_id AND s.tenant_id = slc.tenant_id
                    WHERE s.tenant_id = ? AND s.order_id = ?
                    """,
                    Integer.class,
                    tenantId,
                    orderId);
            return count == null ? 0 : count;
          });
    } finally {
      TenantContext.clear();
    }
  }
}
