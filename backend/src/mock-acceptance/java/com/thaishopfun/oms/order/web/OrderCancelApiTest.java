package com.thaishopfun.oms.order.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.thaishopfun.mocktsf.MockTsfApplication;
import com.thaishopfun.oms.auth.AuthTestSupport;
import com.thaishopfun.oms.catalog.CatalogHttp;
import com.thaishopfun.oms.order.OrderRecipientRepository;
import com.thaishopfun.oms.order.OrderStatusHistoryRepository;
import com.thaishopfun.oms.order.SalesOrder;
import com.thaishopfun.oms.order.SalesOrderRepository;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
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

@ActiveProfiles("test")
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "oms.outbox.publisher-enabled=false")
class OrderCancelApiTest extends OrderIntegrationTest {

  private static ConfigurableApplicationContext mock;

  @Autowired SalesOrderRepository orders;
  @Autowired OrderRecipientRepository recipients;
  @Autowired OrderStatusHistoryRepository history;
  @Autowired PlatformTransactionManager transactions;
  @Autowired JdbcTemplate jdbc;

  private OrderFixture fixture;
  private static final HttpClient HTTP =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

  @DynamicPropertySource
  static void mockTsf(DynamicPropertyRegistry registry) {
    AuthTestSupport.register(registry);
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
  void fixture() {
    fixture = new OrderFixture(orders, recipients, history, transactions);
  }

  @Test
  void ownerCancelIsIdempotent() throws Exception {
    ActiveShop shop = shopActive();
    SalesOrder order = fixture.insert(shop.fixture(), "TSF-240929-000123", "READY_TO_PICK", "NONE");
    CatalogHttp.Result first =
        http.post(
            OrderHttp.ordersPath("/" + order.id() + "/cancel-requests"),
            shop.owner(),
            Map.of("reason", "buyer asked"));
    assertThat(first.status()).isEqualTo(202);
    assertThat(first.body().path("status").asString()).isEqualTo("PENDING");
    long historyRows = historyCount(order.id());
    long auditRows = auditCount(order.id());
    assertThat(historyRows).isGreaterThanOrEqualTo(1);
    assertThat(auditRows).isEqualTo(1);
    assertThat(holdReason(order.id())).isEqualTo("CHANNEL_CANCEL_PENDING");

    CatalogHttp.Result second =
        http.post(
            OrderHttp.ordersPath("/" + order.id() + "/cancel-requests"),
            shop.owner(),
            Map.of("reason", "buyer asked"));
    assertThat(second.status()).isEqualTo(202);
    assertThat(historyCount(order.id())).isEqualTo(historyRows);
    assertThat(auditCount(order.id())).isEqualTo(auditRows);
  }

  @Test
  void staffForbidden() throws Exception {
    ActiveShop shop = shopActive();
    SalesOrder order = fixture.insert(shop.fixture(), "TSF-240929-000124", "READY_TO_PICK", "NONE");
    CatalogHttp.Result result =
        http.post(
            OrderHttp.ordersPath("/" + order.id() + "/cancel-requests"),
            shop.staff(),
            Map.of("reason", "nope"));
    assertThat(result.status()).isEqualTo(403);
    assertThat(result.error()).isEqualTo("FORBIDDEN");
  }

  @Test
  void graceEntitlementBlocksWrite() throws Exception {
    ActiveShop shop = shopActiveGrace();
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
    SalesOrder order = fixture.insert(shop.fixture(), "TSF-240929-000125", "SHIPPED", "NONE");
    CatalogHttp.Result result =
        http.post(
            OrderHttp.ordersPath("/" + order.id() + "/cancel-requests"),
            shop.owner(),
            Map.of("reason", "late"));
    assertThat(result.status()).isEqualTo(409);
    assertThat(result.error()).isEqualTo("ORDER_NOT_CANCELLABLE");
    assertThat(holdReason(order.id())).isEqualTo("NONE");
  }

  @Test
  void channelFaultLeavesOrderUnchanged() throws Exception {
    ActiveShop shop = shopActive();
    SalesOrder order = fixture.insert(shop.fixture(), "TSF-240929-000124", "READY_TO_PICK", "NONE");
    armFault("POST", "/internal/v1/orders/TSF-240929-000124/cancel-requests", 503, 1);
    CatalogHttp.Result result =
        http.post(
            OrderHttp.ordersPath("/" + order.id() + "/cancel-requests"),
            shop.owner(),
            Map.of("reason", "fault"));
    assertThat(result.status()).isIn(502, 503);
    assertThat(holdReason(order.id())).isEqualTo("NONE");
    assertThat(auditCount(order.id())).isZero();
  }

  private ActiveShop shopActive() throws Exception {
    CatalogHttp.Shop httpShop = http.catalog().shop();
    try (Connection admin = AuthTestSupport.admin()) {
      try (PreparedStatement ps =
          admin.prepareStatement("UPDATE tenant SET tsf_shop_id = ? WHERE id = ?")) {
        ps.setString(1, "shop_active");
        ps.setObject(2, httpShop.tenantId());
        ps.executeUpdate();
      }
    }
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
    try (Connection admin = AuthTestSupport.admin()) {
      try (PreparedStatement ps =
          admin.prepareStatement("UPDATE tenant SET tsf_shop_id = ? WHERE id = ?")) {
        ps.setString(1, "shop_active");
        ps.setObject(2, tenantId);
        ps.executeUpdate();
      }
    }
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

  private static void armFault(String method, String path, int status, int times) throws Exception {
    HttpResponse<String> response =
        HTTP.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + mockPort() + "/control/faults"))
                .header("Content-Type", "application/json")
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        """
                        {"method":"%s","path":"%s","status":%d,"times":%d}
                        """
                            .formatted(method, path, status, times),
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
