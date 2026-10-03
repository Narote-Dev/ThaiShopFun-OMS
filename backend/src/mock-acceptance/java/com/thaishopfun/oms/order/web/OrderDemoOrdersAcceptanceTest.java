package com.thaishopfun.oms.order.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.thaishopfun.mocktsf.MockTsfApplication;
import com.thaishopfun.mocktsf.OmsEndpoint;
import com.thaishopfun.oms.auth.AuthTestSupport;
import com.thaishopfun.oms.catalog.CatalogHttp;
import com.thaishopfun.oms.inbox.InboxWorker;
import com.thaishopfun.oms.tenant.TenantContext;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;

@ActiveProfiles("test")
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "oms.outbox.publisher-enabled=false")
class OrderDemoOrdersAcceptanceTest extends OrderIntegrationTest {

  private static final String INBOX_SECRET = "dev-inbox-hmac-secret";
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final HttpClient HTTP =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

  private static ConfigurableApplicationContext mock;

  @Autowired InboxWorker worker;

  @DynamicPropertySource
  static void mockTsf(DynamicPropertyRegistry registry) {
    startMock();
    int mockPort = mockPort();
    registry.add("oms.tsf.base-url", () -> "http://127.0.0.1:" + mockPort);
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
  void pointMockAtOms() {
    mock.getBean(OmsEndpoint.class).setBaseUrl("http://127.0.0.1:" + port);
    TenantContext.clear();
  }

  @AfterEach
  void clearShopActiveTenant() throws Exception {
    TenantContext.clear();
    try (Connection admin = AuthTestSupport.admin();
        var ps = admin.prepareStatement("DELETE FROM tenant WHERE tsf_shop_id = ?")) {
      ps.setString(1, "shop_active");
      ps.executeUpdate();
    }
  }

  @Test
  void demoCatalogHttpAndOrdersSeedProduceExpectedStates() throws Exception {
    provisionShopActive(port);
    HttpResponse<String> catalog =
        HTTP.send(
            HttpRequest.newBuilder(
                    URI.create("http://127.0.0.1:" + port + "/control/demo/order-catalog"))
                .POST(HttpRequest.BodyPublishers.noBody())
                .timeout(Duration.ofSeconds(20))
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    assertThat(catalog.statusCode()).isEqualTo(200);
    assertThat(JSON.readTree(catalog.body()).path("status").asString()).isEqualTo("OK");

    HttpResponse<String> seed =
        HTTP.send(
            HttpRequest.newBuilder(
                    URI.create("http://127.0.0.1:" + mockPort() + "/control/demo/orders-seed"))
                .POST(HttpRequest.BodyPublishers.noBody())
                .timeout(Duration.ofSeconds(60))
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    assertThat(seed.statusCode()).isEqualTo(200);

    int processed;
    int rounds = 0;
    do {
      processed = worker.processAvailable(20);
      rounds++;
    } while (processed > 0 && rounds < 50);
    assertThat(rounds).isLessThan(50);

    assertOrder("DEMO-READY", "READY_TO_PICK", "PAID", "NONE", null);
    assertOrder("DEMO-COD", "READY_TO_PICK", "COD_PENDING", "NONE", null);
    assertOrder("DEMO-OOS", "UNFULFILLED", "COD_PENDING", "OUT_OF_STOCK", null);
    assertOrder("DEMO-UNMAPPED", "UNFULFILLED", "COD_PENDING", "SKU_NOT_MAPPED", null);
    assertOrder(
        "DEMO-BUNDLE", "UNFULFILLED", "COD_PENDING", "OUT_OF_STOCK", "bundle has no components");
    assertOrder("DEMO-CANCELLED", "CANCELLED", "COD_PENDING", "NONE", null);
  }

  private static void provisionShopActive(int omsPort) {
    String owner =
        CatalogHttp.token("owner-demo-" + UUID.randomUUID(), "shop_active", "OWNER", "ACTIVE");
    CatalogHttp.Result me = new CatalogHttp(omsPort).get("/api/v1/me", owner);
    assertThat(me.status()).isEqualTo(200);
  }

  private void assertOrder(
      String externalId, String fulfillment, String payment, String hold, String holdNote)
      throws Exception {
    try (Connection admin = AuthTestSupport.admin();
        PreparedStatement ps =
            admin.prepareStatement(
                """
                SELECT fulfillment_status, payment_status, hold_reason, hold_note
                FROM sales_order
                WHERE external_order_id = ?
                """)) {
      ps.setString(1, externalId);
      try (ResultSet rs = ps.executeQuery()) {
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

  private static void startMock() {
    if (mock != null) {
      return;
    }
    SpringApplication app = MockTsfApplication.application();
    mock =
        app.run(
            "--server.port=0",
            "--server.address=127.0.0.1",
            "--mock.issuer=http://mock-demo.test/tsf-idp",
            "--spring.main.banner-mode=off",
            "--spring.main.register-shutdown-hook=false");
  }

  private static int mockPort() {
    return Integer.parseInt(mock.getEnvironment().getProperty("local.server.port"));
  }
}
