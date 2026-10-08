package com.thaishopfun.oms.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.thaishopfun.mocktsf.OmsEndpoint;
import com.thaishopfun.mocktsf.SeedData;
import com.thaishopfun.mocktsf.idp.TokenIssuer;
import com.thaishopfun.oms.auth.AuthTestSupport;
import com.thaishopfun.oms.auth.UuidV7;
import com.thaishopfun.oms.inbox.InboxWorker;
import com.thaishopfun.oms.order.hold.OrderHoldResolverJob;
import com.thaishopfun.oms.stock.OrderIntakeFaultTestConfig;
import com.thaishopfun.oms.stock.StockFixture;
import com.thaishopfun.oms.stock.StockRepositorySkuOmitTestConfiguration;
import com.thaishopfun.oms.stock.StockSkuLookupTestSupport;
import com.thaishopfun.oms.stock.StockTestConfig.Fault;
import com.thaishopfun.oms.stock.StockTestConfig.FaultHooks;
import io.micrometer.core.instrument.MeterRegistry;
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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.Timeout.ThreadMode;
import org.postgresql.util.PSQLException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@ActiveProfiles("test")
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"spring.main.allow-bean-definition-overriding=true"})
@TestPropertySource(
    properties = {
      "oms.order.hold-resolver.backoff-base=PT1M",
      "oms.order.hold-resolver.backoff-max=PT1H",
      "oms.order.hold-resolver.backoff-jitter=0",
      "oms.order.hold-resolver.batch-size=1"
    })
@Import({
  OrderIntakeT12ScenariosAcceptanceTest.IntakeTestConfig.class,
  OrderIntakeFaultTestConfig.class,
  StockRepositorySkuOmitTestConfiguration.class,
  ListingLifecycleBackoffAcceptanceTest.BackoffClockConfig.class
})
@Timeout(value = 5, unit = TimeUnit.MINUTES, threadMode = ThreadMode.SEPARATE_THREAD)
class ListingLifecycleBackoffAcceptanceTest {

  private static final String INBOX_SECRET = "dev-inbox-hmac-secret";
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final HttpClient HTTP =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
  private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(20);

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
    registry.add("oms.tsf.base-url", () -> "http://127.0.0.1:" + mockPort);
    registry.add("oms.tsf.token-uri", () -> "http://127.0.0.1:" + mockPort + "/tsf-idp/token");
    registry.add("oms.tsf.client-id", () -> "oms-service");
    registry.add("oms.tsf.client-secret", () -> "dev-oms-service-secret");
    registry.add("oms.tsf.audience", () -> "tsf-internal");
    registry.add("oms.channel.tsf.listings-page-limit", () -> "1");
    registry.add("oms.channel.tsf.retry-max-attempts", () -> "1");
    registry.add("oms.channel.tsf.retry-wait-base", () -> "10ms");
    registry.add("oms.channel.tsf.retry-wait-max", () -> "20ms");
  }

  @TestConfiguration
  static class BackoffClockConfig {
    @Bean
    @Primary
    MutableClock holdResolverClock() {
      return new MutableClock(Instant.now().truncatedTo(ChronoUnit.MICROS));
    }
  }

  @LocalServerPort private int port;
  @Autowired InboxWorker worker;
  @Autowired JdbcTemplate jdbc;
  @Autowired PlatformTransactionManager transactions;
  @Autowired OrderHoldResolverJob resolverJob;
  @Autowired FaultHooks faults;
  @Autowired MutableClock clock;
  @Autowired MeterRegistry meters;

  StockFixture fixture;

  @AfterEach
  void teardown() {
    faults.reset();
    StockSkuLookupTestSupport.clearOmitSku();
  }

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
              + "idempotency_key, reconciliation_issue, shadow_diff, order_hold_retry, "
              + "channel_listing CASCADE");
      statement.execute("SET session_replication_role = DEFAULT");
    }
  }

  @Test
  void sweeperFaultBackoffSkipsUntilClockAdvances() throws Exception {
    StockFixture.Shop shop = fixture.shop("ACTIVE");
    String shopId = fixture.tsfShopId(shop);
    UUID account = fixture.tsfChannelAccount(shop, "ACTIVE", "CONNECTED");
    UUID sku = fixture.sku(shop, 10);
    String listingSku = "L-backoff-fault";
    fixture.channelListing(shop, account, listingSku, null, true, false);

    String externalOrderId = "TSF-LC-BO-1-" + UUID.randomUUID();
    ingest(
        OrderIntakeScenarioSupport.orderCreated(
            JSON, externalOrderId, shopId, null, "COD", listingSku, 1, 1));
    worker.processAvailable(10);
    assertThat(holdReason(shop, externalOrderId)).isEqualTo("SKU_NOT_MAPPED");
    fixture.inTenant(
        shop.tenant(),
        () ->
            jdbc.update(
                """
                UPDATE channel_listing
                SET sku_id = ?, mapping_source = 'MANUAL', mapped_at = now()
                WHERE channel_account_id = ? AND external_sku_id = ?
                """,
                sku,
                account,
                listingSku));
    UUID orderId = orderId(shop, externalOrderId);

    faults.failNext(Fault.THROW);
    resolverJob.runScheduledBatch();
    int attempts =
        fixture.inTenant(
            shop.tenant(),
            () ->
                jdbc.queryForObject(
                    "SELECT attempts FROM order_hold_retry WHERE order_id = ?",
                    Integer.class,
                    orderId));
    assertThat(attempts).isEqualTo(1);

    faults.failNext(Fault.THROW);
    resolverJob.runScheduledBatch();
    assertThat(holdReason(shop, externalOrderId)).isEqualTo("SKU_NOT_MAPPED");

    clock.advance(Duration.ofMinutes(61));
    faults.reset();
    resolverJob.runScheduledBatch();
    assertThat(holdReason(shop, externalOrderId)).isEqualTo("NONE");
    assertThat(retryRowCount(shop, orderId)).isZero();
  }

  @Test
  void manualMappingAndHoldRecheckIgnoreBackoff() throws Exception {
    StockFixture.Shop shop = fixture.shop("ACTIVE");
    String shopId = fixture.tsfShopId(shop);
    UUID account = fixture.tsfChannelAccount(shop, "ACTIVE", "CONNECTED");
    UUID sku = fixture.sku(shop, 8);
    String listingSku = "L-backoff-manual";
    fixture.channelListing(shop, account, listingSku, null, true, false);

    String externalOrderId = "TSF-LC-BO-2A-" + UUID.randomUUID();
    ingest(
        OrderIntakeScenarioSupport.orderCreated(
            JSON, externalOrderId, shopId, null, "COD", listingSku, 1, 1));
    worker.processAvailable(10);
    UUID orderId = orderId(shop, externalOrderId);
    seedBackoff(shop, orderId);

    UUID listingId = listingIdFromApi(shop, shopId, account, listingSku);
    String token = userToken(shopId);
    assertThat(httpPutMapping(token, listingId, sku).statusCode()).isEqualTo(200);
    assertThat(holdReason(shop, externalOrderId)).isEqualTo("NONE");
    assertThat(retryRowCount(shop, orderId)).isZero();

    String recheckOrderId = "TSF-LC-BO-2B-" + UUID.randomUUID();
    String recheckListing = "L-backoff-recheck";
    fixture.channelListing(shop, account, recheckListing, null, true, false);
    ingest(
        OrderIntakeScenarioSupport.orderCreated(
            JSON, recheckOrderId, shopId, null, "COD", recheckListing, 1, 1));
    worker.processAvailable(10);
    assertThat(holdReason(shop, recheckOrderId)).isEqualTo("SKU_NOT_MAPPED");
    fixture.inTenant(
        shop.tenant(),
        () ->
            jdbc.update(
                """
                UPDATE channel_listing
                SET sku_id = ?, mapping_source = 'MANUAL', mapped_at = now()
                WHERE channel_account_id = ? AND external_sku_id = ?
                """,
                sku,
                account,
                recheckListing));
    UUID recheckId = orderId(shop, recheckOrderId);
    seedBackoff(shop, recheckId);
    assertThat(httpHoldRecheck(token, recheckId, "bo-recheck-" + UuidV7.generate()).statusCode())
        .isEqualTo(200);
    assertThat(holdReason(shop, recheckOrderId)).isEqualTo("NONE");
  }

  private void seedBackoff(StockFixture.Shop shop, UUID orderId) {
    fixture.inTenant(
        shop.tenant(),
        () ->
            jdbc.update(
                """
                INSERT INTO order_hold_retry (tenant_id, order_id, attempts, next_attempt_at, last_error)
                VALUES (?, ?, 2, now() + interval '1 hour', 'STILL_HELD')
                ON CONFLICT (tenant_id, order_id) DO UPDATE SET
                  attempts = 2, next_attempt_at = now() + interval '1 hour', last_error = 'STILL_HELD'
                """,
                shop.tenant(),
                orderId));
  }

  @Test
  void stillHeldResolvableBacksOff() throws Exception {
    StockFixture.Shop shop = fixture.shop("ACTIVE");
    String shopId = fixture.tsfShopId(shop);
    UUID account = fixture.tsfChannelAccount(shop, "ACTIVE", "CONNECTED");
    UUID sku = fixture.sku(shop, 5);
    String listingSku = "L-backoff-still";
    fixture.channelListing(shop, account, listingSku, null, true, false);
    String externalOrderId = "TSF-LC-BO-3-" + UUID.randomUUID();
    ingest(
        OrderIntakeScenarioSupport.orderCreated(
            JSON, externalOrderId, shopId, null, "COD", listingSku, 1, 1));
    worker.processAvailable(10);
    assertThat(holdReason(shop, externalOrderId)).isEqualTo("SKU_NOT_MAPPED");
    fixture.inTenant(
        shop.tenant(),
        () ->
            jdbc.update(
                """
                UPDATE channel_listing
                SET sku_id = ?, mapping_source = 'MANUAL', mapped_at = now()
                WHERE channel_account_id = ? AND external_sku_id = ?
                """,
                sku,
                account,
                listingSku));
    UUID orderId = orderId(shop, externalOrderId);
    StockSkuLookupTestSupport.omitSkuFromCatalogLookup(sku);

    resolverJob.runScheduledBatch();
    int attempts =
        fixture.inTenant(
            shop.tenant(),
            () ->
                jdbc.queryForObject(
                    "SELECT attempts FROM order_hold_retry WHERE order_id = ?",
                    Integer.class,
                    orderId));
    assertThat(attempts).isEqualTo(1);
    assertThat(holdReason(shop, externalOrderId)).isEqualTo("SKU_NOT_MAPPED");

    StockSkuLookupTestSupport.clearOmitSku();
    clock.advance(Duration.ofMinutes(61));
    resolverJob.runScheduledBatch();
    assertThat(holdReason(shop, externalOrderId)).isEqualTo("NONE");
  }

  @Test
  void deferredCounterIncrementsOnFault() throws Exception {
    double before =
        meters.get(OrderHoldResolverJob.DEFERRED_COUNTER).counter().count();
    StockFixture.Shop shop = fixture.shop("ACTIVE");
    String shopId = fixture.tsfShopId(shop);
    UUID account = fixture.tsfChannelAccount(shop, "ACTIVE", "CONNECTED");
    UUID sku = fixture.sku(shop, 4);
    String listingSku = "L-backoff-metric";
    fixture.channelListing(shop, account, listingSku, null, true, false);
    String externalOrderId = "TSF-LC-BO-4-" + UUID.randomUUID();
    ingest(
        OrderIntakeScenarioSupport.orderCreated(
            JSON, externalOrderId, shopId, null, "COD", listingSku, 1, 1));
    worker.processAvailable(10);
    assertThat(holdReason(shop, externalOrderId)).isEqualTo("SKU_NOT_MAPPED");
    fixture.inTenant(
        shop.tenant(),
        () ->
            jdbc.update(
                """
                UPDATE channel_listing
                SET sku_id = ?, mapping_source = 'MANUAL', mapped_at = now()
                WHERE channel_account_id = ? AND external_sku_id = ?
                """,
                sku,
                account,
                listingSku));
    faults.failNext(Fault.THROW);
    resolverJob.runScheduledBatch();
    double after = meters.get(OrderHoldResolverJob.DEFERRED_COUNTER).counter().count();
    assertThat(after).isGreaterThan(before);
  }

  @Test
  void orderHoldRetryRlsRejectsCrossTenantInsert() throws Exception {
    StockFixture.Shop shopA = fixture.shop("ACTIVE");
    StockFixture.Shop shopB = fixture.shop("ACTIVE");
    UUID orderA = UuidV7.generate();
    UUID orderB = UuidV7.generate();
    UUID accountA = fixture.tsfChannelAccount(shopA, "ACTIVE", "CONNECTED");
    seedOrder(shopA, accountA, orderA, "TSF-RLS-A");
    seedOrder(shopB, fixture.tsfChannelAccount(shopB, "ACTIVE", "CONNECTED"), orderB, "TSF-RLS-B");

    try (Connection app = AuthTestSupport.app()) {
      app.setAutoCommit(false);
      try (PreparedStatement tenant =
          app.prepareStatement("SELECT set_config('app.tenant_id', ?, true)")) {
        tenant.setString(1, shopA.tenant().toString());
        tenant.execute();
      }
      try (PreparedStatement insert =
          app.prepareStatement(
              """
              INSERT INTO order_hold_retry (tenant_id, order_id, attempts, next_attempt_at, last_error)
              VALUES (?, ?, 1, now() + interval '1 minute', 'TEST')
              """)) {
        insert.setObject(1, shopA.tenant());
        insert.setObject(2, orderA);
        insert.executeUpdate();
      }
      try (PreparedStatement cross =
          app.prepareStatement(
              """
              INSERT INTO order_hold_retry (tenant_id, order_id, attempts, next_attempt_at, last_error)
              VALUES (?, ?, 1, now() + interval '1 minute', 'TEST')
              """)) {
        cross.setObject(1, shopB.tenant());
        cross.setObject(2, orderB);
        assertThatThrownBy(cross::executeUpdate).isInstanceOf(PSQLException.class);
      }
      app.rollback();
    }
  }

  private void seedOrder(StockFixture.Shop shop, UUID account, UUID orderId, String externalId) {
    fixture.inTenant(
        shop.tenant(),
        () ->
            jdbc.update(
                """
                INSERT INTO sales_order (
                  id, tenant_id, channel_account_id, external_order_id, order_status,
                  fulfillment_status, payment_status, payment_method, hold_reason, ordered_at
                ) VALUES (?, ?, ?, ?, 'ACTIVE', 'UNFULFILLED', 'UNPAID', 'COD', 'SKU_NOT_MAPPED', now())
                """,
                orderId,
                shop.tenant(),
                account,
                externalId));
  }

  private long retryRowCount(StockFixture.Shop shop, UUID orderId) {
    return fixture.inTenant(
        shop.tenant(),
        () ->
            jdbc.queryForObject(
                "SELECT count(*) FROM order_hold_retry WHERE order_id = ?", Long.class, orderId));
  }

  private UUID orderId(StockFixture.Shop shop, String externalOrderId) {
    return fixture.inTenant(
        shop.tenant(),
        () ->
            jdbc.queryForObject(
                "SELECT id FROM sales_order WHERE external_order_id = ?",
                UUID.class,
                externalOrderId));
  }

  private String holdReason(StockFixture.Shop shop, String externalOrderId) {
    return fixture.inTenant(
        shop.tenant(),
        () ->
            jdbc.queryForObject(
                "SELECT hold_reason FROM sales_order WHERE external_order_id = ?",
                String.class,
                externalOrderId));
  }

  private UUID listingIdFromApi(
      StockFixture.Shop shop, String shopId, UUID channelAccountId, String externalSkuId)
      throws Exception {
    HttpResponse<String> list =
        HTTP.send(
            HttpRequest.newBuilder(
                    URI.create(
                        "http://127.0.0.1:"
                            + port
                            + "/api/v1/channel-listings?channel_account_id="
                            + channelAccountId
                            + "&limit=200"))
                .timeout(HTTP_TIMEOUT)
                .header("Authorization", "Bearer " + userToken(shopId))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    assertThat(list.statusCode()).isEqualTo(200);
    for (var item : JSON.readTree(list.body()).path("items")) {
      if (externalSkuId.equals(item.path("external_sku_id").asString())) {
        return UUID.fromString(item.path("id").asString());
      }
    }
    throw new AssertionError("listing not found: " + externalSkuId);
  }

  private HttpResponse<String> httpPutMapping(String token, UUID listingId, UUID sku)
      throws Exception {
    return HTTP.send(
        HttpRequest.newBuilder(
                URI.create(
                    "http://127.0.0.1:"
                        + port
                        + "/api/v1/channel-listings/"
                        + listingId
                        + "/mapping"))
            .timeout(HTTP_TIMEOUT)
            .header("Authorization", "Bearer " + token)
            .header("Content-Type", "application/json")
            .PUT(
                HttpRequest.BodyPublishers.ofString(
                    "{\"sku_id\":\"" + sku + "\"}", StandardCharsets.UTF_8))
            .build(),
        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  private HttpResponse<String> httpHoldRecheck(String token, UUID orderId, String idempotencyKey)
      throws Exception {
    return HTTP.send(
        HttpRequest.newBuilder(
                URI.create(
                    "http://127.0.0.1:" + port + "/api/v1/orders/" + orderId + "/hold-rechecks"))
            .timeout(HTTP_TIMEOUT)
            .header("Authorization", "Bearer " + token)
            .header("Idempotency-Key", idempotencyKey)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{}"))
            .build(),
        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
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
            .header(
                "Authorization",
                "Bearer "
                    + OrderIntakeMockRuntime.mock().getBean(TokenIssuer.class).tsfServiceToken())
            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
            .build();
    assertThat(HTTP.send(request, HttpResponse.BodyHandlers.ofString()).statusCode())
        .isEqualTo(202);
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
}
