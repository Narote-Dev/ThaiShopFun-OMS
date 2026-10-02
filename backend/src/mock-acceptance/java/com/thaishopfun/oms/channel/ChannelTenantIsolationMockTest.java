package com.thaishopfun.oms.channel;

import static org.assertj.core.api.Assertions.assertThat;

import com.thaishopfun.mocktsf.MockTsfApplication;
import com.thaishopfun.mocktsf.OmsEndpoint;
import com.thaishopfun.oms.auth.AuthTestSupport;
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
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** JIT tenant provisioning via mock-tsf control events and checkout reservation isolation (T16). */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ChannelTenantIsolationMockTest {

  private static final String ISSUER = "http://mock-tsf-isolation.test/tsf-idp";
  private static final String INBOX_SECRET = "dev-inbox-hmac-secret";
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final HttpClient HTTP =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

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
    registry.add("oms.security.internal-client-ids", () -> "tsf");
    registry.add("oms.inbox.hmac-secrets", () -> INBOX_SECRET);
    registry.add("oms.inbox.worker-enabled", () -> "false");
  }

  @AfterAll
  static void stopMock() {
    if (mock != null) {
      mock.close();
    }
  }

  @LocalServerPort private int port;
  @Autowired private InboxWorker worker;

  @BeforeEach
  void pointMockAtOms() throws Exception {
    mock.getBean(OmsEndpoint.class).setBaseUrl("http://127.0.0.1:" + port);
    try (Connection admin = AuthTestSupport.admin();
        var statement = admin.createStatement()) {
      statement.execute("TRUNCATE TABLE inbox_event");
    }
  }

  @AfterEach
  void clearTenant() {
    TenantContext.clear();
  }

  @Test
  void jitProvisionedShopGetsChannelAccountAndCheckout() throws Exception {
    String shopId = "shop-isolation-" + UUID.randomUUID();
    ObjectNode event = membership(UUID.randomUUID().toString(), shopId, 1);
    HttpResponse<String> provision =
        HTTP.send(
            HttpRequest.newBuilder(mockUri("/control/events/send"))
                .header("Content-Type", "application/json")
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        JSON.createObjectNode().set("event", event).toString()))
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    assertThat(provision.statusCode()).isEqualTo(200);
    assertThat(worker.processAvailable(10)).isEqualTo(1);

    UUID tenantId = resolveTenant(shopId);
    assertThat(tenantId).isNotNull();
    // Step 1: JIT provisioning creates the default TSF channel_account row.
    try (Connection admin = AuthTestSupport.admin();
        PreparedStatement statement =
            admin.prepareStatement(
                """
                SELECT mode, status, tenant_id
                FROM channel_account
                WHERE channel = 'TSF' AND external_shop_id = ?
                """)) {
      statement.setString(1, shopId);
      try (ResultSet rs = statement.executeQuery()) {
        assertThat(rs.next()).isTrue();
        assertThat(rs.getString("mode")).isEqualTo("OBSERVE");
        assertThat(rs.getString("status")).isEqualTo("CONNECTED");
        assertThat(rs.getObject("tenant_id", UUID.class)).isEqualTo(tenantId);
        assertThat(rs.next()).isFalse();
      }
    }

    String request =
        "{\"checkout_id\":\"chk-isolation\",\"tsf_shop_id\":\""
            + shopId
            + "\",\"items\":[{\"listing_sku_id\":\"L-1\",\"qty\":1}]}";
    HttpResponse<String> reserve =
        HTTP.send(
            HttpRequest.newBuilder(mockUri("/control/checkout/reservations"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(request))
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    assertThat(reserve.statusCode()).isEqualTo(200);
    JsonNode reserveBody = JSON.readTree(reserve.body());
    // Step 2: Checkout reservation reaches OMS for the provisioned shop only.
    assertThat(reserveBody.path("response_schema_valid").asBoolean()).isTrue();
    assertThat(reserveBody.path("oms_status").asInt()).isEqualTo(201);
  }

  private static UUID resolveTenant(String shopId) throws Exception {
    try (Connection app = AuthTestSupport.app();
        PreparedStatement statement = app.prepareStatement("SELECT resolve_tenant('TSF', ?)")) {
      statement.setString(1, shopId);
      try (ResultSet rs = statement.executeQuery()) {
        assertThat(rs.next()).isTrue();
        return rs.getObject(1, UUID.class);
      }
    }
  }

  private static ObjectNode membership(String eventId, String shopId, int entVer) {
    ObjectNode data = JSON.createObjectNode();
    data.put("name", "Shop " + shopId);
    data.put("tier", "PRO");
    data.put("status", "ACTIVE");
    data.put("ent_ver", entVer);
    data.put("expires_at", "2027-01-01T00:00:00Z");
    ObjectNode event = JSON.createObjectNode();
    event.put("event_id", eventId);
    event.put("event_type", "membership.changed");
    event.put("schema_version", 1);
    event.put("occurred_at", Instant.now().truncatedTo(ChronoUnit.SECONDS).toString());
    event.put("tsf_shop_id", shopId);
    event.put("aggregate_id", shopId);
    event.put("aggregate_version", entVer);
    event.set("data", data);
    return event;
  }

  private static URI mockUri(String path) {
    return URI.create("http://127.0.0.1:" + mockPort() + path);
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
            "--spring.main.banner-mode=off",
            "--spring.main.register-shutdown-hook=false");
  }

  private static int mockPort() {
    String port = mock.getEnvironment().getProperty("local.server.port");
    if (port == null || port.isBlank()) {
      throw new IllegalStateException("mock-tsf did not bind a port");
    }
    return Integer.parseInt(port);
  }
}
