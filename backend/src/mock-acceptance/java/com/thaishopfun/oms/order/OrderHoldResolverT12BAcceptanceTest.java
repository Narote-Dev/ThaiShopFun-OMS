package com.thaishopfun.oms.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.thaishopfun.mocktsf.OmsEndpoint;
import com.thaishopfun.mocktsf.SeedData;
import com.thaishopfun.mocktsf.idp.TokenIssuer;
import com.thaishopfun.oms.auth.AuthTestSupport;
import com.thaishopfun.oms.auth.UuidV7;
import com.thaishopfun.oms.inbox.InboxWorker;
import com.thaishopfun.oms.stock.StockFixture;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
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
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@ActiveProfiles("test")
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.main.allow-bean-definition-overriding=true",
      "oms.order.hold-resolver.enabled=false"
    })
@Import(OrderIntakeT12ScenariosAcceptanceTest.IntakeTestConfig.class)
class OrderHoldResolverT12BAcceptanceTest {

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

  StockFixture fixture;
  @BeforeEach
  void setup() throws Exception {
    OrderIntakeMockRuntime.mock().getBean(OmsEndpoint.class).setBaseUrl("http://127.0.0.1:" + port);
    fixture = new StockFixture(jdbc, transactions);
    try (Connection admin = AuthTestSupport.admin();
        var statement = admin.createStatement()) {
      statement.execute("SET session_replication_role = replica");
      statement.execute(
          "TRUNCATE TABLE audit_log, outbox_event, inbox_event, order_status_history, order_line, "
              + "order_recipient, sales_order, stock_reservation, inventory_ledger, inventory, "
              + "idempotency_key, reconciliation_issue, shadow_diff, channel_listing CASCADE");
      statement.execute("SET session_replication_role = DEFAULT");
    }
  }

  @Test
  void manualMappingReleasesSkuNotMappedHoldAndReserves() throws Exception {
    StockFixture.Shop shop = fixture.shop("ACTIVE");
    String shopId = fixture.tsfShopId(shop);
    String token = userToken(shopId);
    UUID account = fixture.tsfChannelAccount(shop, "ACTIVE", "CONNECTED");
    UUID sku = fixture.sku(shop, 20);
    fixture.channelListing(shop, account, "L-unmapped", null, true, false);

    String externalOrderId = "TSF-MAP-" + UUID.randomUUID();
    ObjectNode created =
        OrderIntakeScenarioSupport.orderCreated(
            JSON, externalOrderId, shopId, null, "COD", "L-unmapped", 2, 1);
    ingest(created);
    worker.processAvailable(10);

    String hold =
        fixture.inTenant(
            shop.tenant(),
            () ->
                jdbc.queryForObject(
                    "SELECT hold_reason FROM sales_order WHERE external_order_id = ?",
                    String.class,
                    externalOrderId));
    assertThat(hold).isEqualTo("SKU_NOT_MAPPED");

    UUID listingId =
        fixture.inTenant(
            shop.tenant(),
            () ->
                jdbc.queryForObject(
                    "SELECT id FROM channel_listing WHERE external_sku_id = 'L-unmapped'",
                    UUID.class));

    HttpResponse<String> mapResponse =
        HTTP.send(
            HttpRequest.newBuilder(
                    URI.create(
                        "http://127.0.0.1:" + port + "/api/v1/channel-listings/" + listingId + "/mapping"))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString("{\"sku_id\":\"" + sku + "\"}"))
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    assertThat(mapResponse.statusCode()).isEqualTo(200);

    hold =
        fixture.inTenant(
            shop.tenant(),
            () ->
                jdbc.queryForObject(
                    "SELECT hold_reason FROM sales_order WHERE external_order_id = ?",
                    String.class,
                    externalOrderId));
    assertThat(hold).isEqualTo("NONE");
    Long reservations =
        fixture.inTenant(
            shop.tenant(),
            () ->
                jdbc.queryForObject(
                    """
                    SELECT count(*) FROM stock_reservation sr
                    JOIN sales_order so ON so.id::text = sr.owner_ref
                    WHERE so.external_order_id = ? AND sr.owner_type = 'ORDER' AND sr.status = 'ACTIVE'
                    """,
                    Long.class,
                    externalOrderId));
    assertThat(reservations).isEqualTo(1);
  }

  @Test
  void holdRecheckIsIdempotent() throws Exception {
    StockFixture.Shop shop = fixture.shop("ACTIVE");
    String shopId = fixture.tsfShopId(shop);
    String token = userToken(shopId);
    UUID account = fixture.tsfChannelAccount(shop, "ACTIVE", "CONNECTED");
    UUID sku = fixture.sku(shop, 0);
    fixture.channelListing(shop, account, "L-oos", sku, true);

    String externalOrderId = "TSF-OOS-" + UUID.randomUUID();
    ObjectNode created =
        OrderIntakeScenarioSupport.orderCreated(
            JSON, externalOrderId, shopId, null, "COD", "L-oos", 1, 1);
    ingest(created);
    worker.processAvailable(10);
    UUID orderId =
        fixture.inTenant(
            shop.tenant(),
            () ->
                jdbc.queryForObject(
                    "SELECT id FROM sales_order WHERE external_order_id = ?",
                    UUID.class,
                    externalOrderId));

    String key = "recheck-" + UuidV7.generate();
    HttpRequest request =
        HttpRequest.newBuilder(
                URI.create(
                    "http://127.0.0.1:" + port + "/api/v1/orders/" + orderId + "/hold-rechecks"))
            .header("Authorization", "Bearer " + token)
            .header("Idempotency-Key", key)
            .POST(HttpRequest.BodyPublishers.noBody())
            .build();
    HttpResponse<String> first =
        HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    HttpResponse<String> second =
        HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    assertThat(first.statusCode()).isEqualTo(200);
    assertThat(second.statusCode()).isEqualTo(200);
    assertThat(first.body()).isEqualTo(second.body());
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
            .header("Authorization", "Bearer " + OrderIntakeMockRuntime.mock().getBean(TokenIssuer.class).tsfServiceToken())
            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
            .build();
    assertThat(HTTP.send(request, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(202);
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

  private String userToken(String shopId) throws Exception {
    TokenIssuer issuer = OrderIntakeMockRuntime.mock().getBean(TokenIssuer.class);
    SeedData.ShopUser user =
        new SeedData.ShopUser(
            "owner-" + UuidV7.generate(),
            "owner@test.local",
            shopId,
            "Test Shop",
            "OWNER",
            "PRO",
            "ACTIVE",
            Instant.now().plus(30, ChronoUnit.DAYS),
            1);
    String token = issuer.userAccessToken(user);
    HttpResponse<String> me =
        HTTP.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/me"))
                .header("Authorization", "Bearer " + token)
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    assertThat(me.statusCode()).isEqualTo(200);
    return token;
  }
}
