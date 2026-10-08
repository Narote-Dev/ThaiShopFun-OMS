package com.thaishopfun.oms.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.thaishopfun.mocktsf.OmsEndpoint;
import com.thaishopfun.mocktsf.SeedData;
import com.thaishopfun.mocktsf.SeedData.ShopUser;
import com.thaishopfun.mocktsf.idp.TokenIssuer;
import com.thaishopfun.oms.auth.AuthTestSupport;
import com.thaishopfun.oms.auth.UuidV7;
import com.thaishopfun.oms.inbox.InboxWorker;
import com.thaishopfun.oms.order.demo.OrderDemoCatalogService;
import com.thaishopfun.oms.order.hold.OrderHoldResolverJob;
import com.thaishopfun.oms.stock.OrderIntakeFaultTestConfig;
import com.thaishopfun.oms.stock.StockFixture;
import com.thaishopfun.oms.stock.StockRepositorySkuOmitTestConfiguration;
import com.thaishopfun.oms.stock.StockSkuLookupTestSupport;
import com.thaishopfun.oms.stock.StockTestConfig.FaultHooks;
import com.thaishopfun.oms.tenant.TenantContext;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.Timeout.ThreadMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@ActiveProfiles("test")
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.main.allow-bean-definition-overriding=true",
      "oms.order.hold-resolver.enabled=false"
    })
@Import({
  OrderIntakeT12ScenariosAcceptanceTest.IntakeTestConfig.class,
  OrderIntakeFaultTestConfig.class,
  StockRepositorySkuOmitTestConfiguration.class
})
@Timeout(value = 5, unit = TimeUnit.MINUTES, threadMode = ThreadMode.SEPARATE_THREAD)
class ListingLifecycleT12BFUAcceptanceTest {

  private static final String INBOX_SECRET = "dev-inbox-hmac-secret";
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final HttpClient HTTP =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
  private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(20);

  private static final List<String> MOCK_LISTING_IDS =
      List.of(
          "tsf_sku_7781",
          "tsf_sku_9001",
          "tsf_sku_5000",
          "L-demo-missing",
          "L-demo-ready",
          "L-demo-cod",
          "L-demo-oos",
          "L-demo-bundle",
          "L-demo-cancel");

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

  @LocalServerPort private int port;
  @Autowired OrderDemoCatalogService catalog;
  @Autowired InboxWorker worker;
  @Autowired JdbcTemplate jdbc;
  @Autowired PlatformTransactionManager transactions;
  @Autowired OrderHoldResolverJob resolverJob;
  @Autowired FaultHooks faults;

  StockFixture fixture;
  private final java.util.concurrent.atomic.AtomicLong listingAggregateVersion =
      new java.util.concurrent.atomic.AtomicLong(0);

  @AfterAll
  static void stopMockTsf() {
    OrderIntakeMockRuntime.stopMock();
  }

  @AfterEach
  void teardown() throws Exception {
    faults.reset();
    StockSkuLookupTestSupport.clearOmitSku();
    unhideAllMockListings();
    TenantContext.clear();
  }

  @BeforeEach
  void setup() throws Exception {
    listingAggregateVersion.set(0);
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
  void demoCatalogSyncPreservesSixDemoListings() throws Exception {
    StockFixture.Shop shop = ensureShopActive();
    String shopId = "shop_active";
    UUID account = fixture.channelAccount(shop, shopId, "ACTIVE", "CONNECTED");
    TenantContext.set(shop.tenant(), null);
    assertThat(catalog.ensureDemoCatalog().get("status")).isEqualTo("OK");

    HttpResponse<String> sync = postListingSync(shopId, account);
    assertThat(sync.statusCode()).isEqualTo(202);
    JsonNode body = JSON.readTree(sync.body());
    assertThat(body.path("removed").asInt()).isZero();
    assertThat(body.path("removal_skipped").asBoolean()).isFalse();

    long demoActive =
        fixture.inTenant(
            shop.tenant(),
            () ->
                jdbc.queryForObject(
                    """
                    SELECT count(*) FROM channel_listing
                    WHERE channel_account_id = ?
                      AND external_sku_id LIKE 'L-demo-%'
                      AND removed_at IS NULL
                    """,
                    Long.class,
                    account));
    assertThat(demoActive).isEqualTo(6);

    UUID missingSku =
        fixture.inTenant(
            shop.tenant(),
            () ->
                jdbc.queryForObject(
                    """
                    SELECT sku_id FROM channel_listing
                    WHERE channel_account_id = ? AND external_sku_id = 'L-demo-missing'
                    """,
                    UUID.class,
                    account));
    assertThat(missingSku).isNull();
  }

  @Test
  void ac02_reviveViaSyncReleasesInSyncCall() throws Exception {
    StockFixture.Shop shop = ensureShopActive();
    String shopId = "shop_active";
    UUID account = fixture.channelAccount(shop, shopId, "ACTIVE", "CONNECTED");
    UUID sku = fixture.sku(shop, 12);
    String listingSku = "tsf_sku_7781";
    assertThat(postListingSync(shopId, account).statusCode()).isEqualTo(202);
    fixture.inTenant(
        shop.tenant(),
        () ->
            jdbc.update(
                """
                UPDATE channel_listing
                SET sku_id = NULL, mapping_source = NULL, mapped_at = NULL
                WHERE channel_account_id = ? AND external_sku_id = ?
                """,
                account,
                listingSku));

    String externalOrderId = "TSF-LC-AC02-" + UUID.randomUUID();
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
                SET sku_id = ?, mapping_source = 'MANUAL', mapped_at = now(), removed_at = now()
                WHERE channel_account_id = ? AND external_sku_id = ?
                """,
                sku,
                account,
                listingSku));

    HttpResponse<String> sync = postListingSync(shopId, account);
    assertThat(sync.statusCode()).isEqualTo(202);
    JsonNode syncBody = JSON.readTree(sync.body());
    assertThat(syncBody.path("revived").asInt()).isGreaterThanOrEqualTo(1);
    assertThat(syncBody.path("reevaluated_orders").asInt()).isGreaterThanOrEqualTo(1);
    assertThat(holdReason(shop, externalOrderId)).isEqualTo("NONE");
    assertThat(resolverJob.runScheduledBatch()).isZero();
  }

  @Test
  void ac03_listingChangedReviveSweeperEngineFree() throws Exception {
    StockFixture.Shop shop = ensureShopActive();
    String shopId = "shop_active";
    UUID account = fixture.channelAccount(shop, shopId, "ACTIVE", "CONNECTED");
    UUID sku = fixture.sku(shop, 8);
    String listingSku = "L-lc-sweeper";
    fixture.channelListing(shop, account, listingSku, sku, true);

    String externalOrderId = "TSF-LC-AC03-" + UUID.randomUUID();
    ingest(
        OrderIntakeScenarioSupport.orderCreated(
            JSON, externalOrderId, shopId, null, "COD", listingSku, 1, 1));
    worker.processAvailable(10);

    fixture.inTenant(
        shop.tenant(),
        () ->
            jdbc.update(
                """
                UPDATE channel_listing SET removed_at = now()
                WHERE channel_account_id = ? AND external_sku_id = ?
                """,
                account,
                listingSku));
    fixture.inTenant(
        shop.tenant(),
        () ->
            jdbc.update(
                "UPDATE sales_order SET hold_reason = 'SKU_NOT_MAPPED' WHERE external_order_id = ?",
                externalOrderId));

    long reservationsBefore = stockReservationCount(shop);
    listingChanged(shopId, listingSku, "UPSERT", "LC-SWEEPER", "Revived via event");
    worker.processAvailable(10);
    assertThat(stockReservationCount(shop)).isEqualTo(reservationsBefore);

    resolverJob.runScheduledBatch();
    assertThat(holdReason(shop, externalOrderId)).isEqualTo("NONE");
    assertThat(activeOrderReservations(shop, externalOrderId)).isEqualTo(1);
  }

  @Test
  void ac04_hideUnhide() throws Exception {
    StockFixture.Shop shop = ensureShopActive();
    String shopId = "shop_active";
    UUID account = fixture.channelAccount(shop, shopId, "ACTIVE", "CONNECTED");
    postListingSync(shopId, account);

    setListingHidden("tsf_sku_9001", true);
    HttpResponse<String> hideSync = postListingSync(shopId, account);
    assertThat(hideSync.statusCode()).isEqualTo(202);
    assertThat(JSON.readTree(hideSync.body()).path("removed").asInt()).isEqualTo(1);

    setListingHidden("tsf_sku_9001", false);
    HttpResponse<String> unhideSync = postListingSync(shopId, account);
    assertThat(unhideSync.statusCode()).isEqualTo(202);
    assertThat(JSON.readTree(unhideSync.body()).path("revived").asInt()).isEqualTo(1);
  }

  @Test
  void ac04_faultedSecondPageNoRemoval() throws Exception {
    StockFixture.Shop shop = ensureShopActive();
    String shopId = "shop_active";
    UUID account = fixture.channelAccount(shop, shopId, "ACTIVE", "CONNECTED");
    postListingSync(shopId, account);
    armFault("GET", "/internal/v1/shops/shop_active/listings", 503, 1);

    HttpResponse<String> sync = postListingSync(shopId, account);
    if (sync.statusCode() == 202) {
      assertThat(JSON.readTree(sync.body()).path("removed").asInt()).isZero();
    } else {
      assertThat(sync.statusCode()).isEqualTo(503);
    }
  }

  @Test
  void ac04_guardSkipsMassRemoval() throws Exception {
    StockFixture.Shop shop = ensureShopActive();
    String shopId = "shop_active";
    UUID account = fixture.channelAccount(shop, shopId, "ACTIVE", "CONNECTED");
    postListingSync(shopId, account);
    for (int i = 0; i < 2; i++) {
      fixture.channelListing(shop, account, "L-guard-extra-" + i, null, true, false);
    }
    long active =
        fixture.inTenant(
            shop.tenant(),
            () ->
                jdbc.queryForObject(
                    """
                    SELECT count(*) FROM channel_listing
                    WHERE channel_account_id = ? AND removed_at IS NULL
                    """,
                    Long.class,
                    account));
    assertThat(active).isGreaterThanOrEqualTo(10);

    for (String id : MOCK_LISTING_IDS) {
      if (!id.startsWith("L-demo-missing")) {
        setListingHidden(id, true);
      }
    }

    HttpResponse<String> sync = postListingSync(shopId, account);
    assertThat(sync.statusCode()).isEqualTo(202);
    JsonNode body = JSON.readTree(sync.body());
    assertThat(body.path("removal_skipped").asBoolean()).isTrue();
    assertThat(body.path("removed").asInt()).isZero();
  }

  @Test
  void ac04_emptyFeedNoRemoval() throws Exception {
    StockFixture.Shop shop = ensureShopActive();
    String shopId = "shop_active";
    UUID account = fixture.channelAccount(shop, shopId, "ACTIVE", "CONNECTED");
    postListingSync(shopId, account);
    for (String id : MOCK_LISTING_IDS) {
      setListingHidden(id, true);
    }

    HttpResponse<String> sync = postListingSync(shopId, account);
    assertThat(sync.statusCode()).isEqualTo(202);
    assertThat(JSON.readTree(sync.body()).path("removed").asInt()).isZero();
  }

  @Test
  void ac04_concurrentUpsertProtected() throws Exception {
    StockFixture.Shop shop = ensureShopActive();
    String shopId = "shop_active";
    UUID account = fixture.channelAccount(shop, shopId, "ACTIVE", "CONNECTED");
    postListingSync(shopId, account);
    String listingSku = "tsf_sku_9001";
    setListingHidden(listingSku, true);
    fixture.inTenant(
        shop.tenant(),
        () ->
            jdbc.update(
                """
                UPDATE channel_listing SET updated_at = now() - interval '1 hour'
                WHERE channel_account_id = ? AND external_sku_id = ?
                """,
                account,
                listingSku));

    ExecutorService pool = Executors.newSingleThreadExecutor();
    try {
      Future<HttpResponse<String>> syncFuture = pool.submit(() -> postListingSync(shopId, account));
      Thread.sleep(80);
      setListingHidden(listingSku, false);
      listingChanged(shopId, listingSku, "UPSERT", "MUG-WHT", "แก้วขาว");
      worker.processAvailable(10);
      HttpResponse<String> sync = syncFuture.get(60, TimeUnit.SECONDS);
      assertThat(sync.statusCode()).isEqualTo(202);
      assertThat(removedAt(shop, account, listingSku)).isNull();
    } finally {
      pool.shutdownNow();
    }
  }

  @Nested
  @TestPropertySource(properties = {"oms.order.hold-resolver.reeval-cap=1"})
  class ReevalCapOne {
    @Test
    void ac06_deferredWithReevalCapOne() throws Exception {
      StockFixture.Shop shop = ensureShopActive();
      String shopId = "shop_active";
      UUID account = fixture.channelAccount(shop, shopId, "ACTIVE", "CONNECTED");
      assertThat(postListingSync(shopId, account).statusCode()).isEqualTo(202);
      ensureSellerSku(shop, "TSHIRT-BLK-M", 50);
      ensureSellerSku(shop, "MUG-WHT", 50);
      fixture.inTenant(
          shop.tenant(),
          () ->
              jdbc.update(
                  """
                  UPDATE channel_listing
                  SET sku_id = NULL, mapping_source = NULL, mapped_at = NULL
                  WHERE channel_account_id = ?
                    AND external_sku_id IN ('tsf_sku_7781', 'tsf_sku_9001')
                  """,
                  account));

      for (int i = 0; i < 3; i++) {
        String externalOrderId = "TSF-LC-AC06-" + i + "-" + UUID.randomUUID();
        String listingSku = i < 2 ? "tsf_sku_7781" : "tsf_sku_9001";
        ingest(
            OrderIntakeScenarioSupport.orderCreated(
                JSON, externalOrderId, shopId, null, "COD", listingSku, 1, 1));
        worker.processAvailable(10);
        assertThat(holdReason(shop, externalOrderId)).isEqualTo("SKU_NOT_MAPPED");
      }

      HttpResponse<String> sync = postListingSync(shopId, account);
      assertThat(sync.statusCode()).isEqualTo(202);
      JsonNode body = JSON.readTree(sync.body());
      assertThat(body.path("auto_mapped").asInt()).isGreaterThanOrEqualTo(2);
      assertThat(body.path("deferred").asInt()).isGreaterThanOrEqualTo(2);
    }
  }

  private StockFixture.Shop ensureShopActive() throws Exception {
    SeedData seeds = OrderIntakeMockRuntime.mock().getBean(SeedData.class);
    TokenIssuer issuer = OrderIntakeMockRuntime.mock().getBean(TokenIssuer.class);
    ShopUser owner = seeds.find("owner-active").orElseThrow();
    String token = issuer.userAccessToken(owner);
    HttpResponse<String> me =
        HTTP.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/me"))
                .header("Authorization", "Bearer " + token)
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    assertThat(me.statusCode()).isEqualTo(200);
    UUID tenantId = UUID.fromString(JSON.readTree(me.body()).path("tenant").path("id").asString());
    return shopShell(tenantId);
  }

  private StockFixture.Shop shopShell(UUID tenantId) {
    return fixture.inTenant(
        tenantId,
        () -> {
          List<UUID> products =
              jdbc.query(
                  "SELECT id FROM product LIMIT 1", (rs, row) -> rs.getObject("id", UUID.class));
          UUID product = products.isEmpty() ? UuidV7.generate() : products.get(0);
          if (products.isEmpty()) {
            jdbc.update(
                "INSERT INTO product (id, tenant_id, name, status) VALUES (?, ?, 'Active', 'ACTIVE')",
                product,
                tenantId);
          }
          List<UUID> warehouses =
              jdbc.query(
                  "SELECT id FROM warehouse WHERE tenant_id = ? LIMIT 1",
                  (rs, row) -> rs.getObject("id", UUID.class),
                  tenantId);
          UUID warehouse = warehouses.isEmpty() ? UuidV7.generate() : warehouses.get(0);
          if (warehouses.isEmpty()) {
            jdbc.update(
                """
                INSERT INTO warehouse (id, tenant_id, code, name, is_default)
                VALUES (?, ?, 'WH-MAIN', 'Main', true)
                """,
                warehouse,
                tenantId);
          }
          return new StockFixture.Shop(tenantId, product, warehouse);
        });
  }

  private HttpResponse<String> postListingSync(String shopId, UUID account) throws Exception {
    return HTTP.send(
        HttpRequest.newBuilder(
                URI.create(
                    "http://127.0.0.1:"
                        + port
                        + "/api/v1/channel-accounts/"
                        + account
                        + "/listing-syncs"))
            .timeout(HTTP_TIMEOUT)
            .header("Authorization", "Bearer " + userToken(shopId))
            .POST(HttpRequest.BodyPublishers.noBody())
            .build(),
        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  private void setListingHidden(String listingSkuId, boolean hidden) throws Exception {
    ObjectNode body = JSON.createObjectNode();
    body.put("hidden", hidden);
    HttpResponse<String> response =
        HTTP.send(
            HttpRequest.newBuilder(
                    URI.create(
                        "http://127.0.0.1:"
                            + OrderIntakeMockRuntime.mockPort()
                            + "/control/listings/"
                            + listingSkuId
                            + "/hidden"))
                .timeout(HTTP_TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    assertThat(response.statusCode()).isEqualTo(200);
  }

  private void unhideAllMockListings() throws Exception {
    for (String id : MOCK_LISTING_IDS) {
      setListingHidden(id, false);
    }
  }

  private void listingChanged(
      String shopId, String listingSkuId, String action, String sellerSku, String name)
      throws Exception {
    ObjectNode body = JSON.createObjectNode();
    body.put("tsf_shop_id", shopId);
    body.put("listing_sku_id", listingSkuId);
    body.put("action", action);
    body.put("aggregate_version", listingAggregateVersion.incrementAndGet());
    if (sellerSku != null) {
      body.put("seller_sku", sellerSku);
    }
    if (name != null) {
      body.put("name", name);
    }
    HttpResponse<String> response =
        HTTP.send(
            HttpRequest.newBuilder(
                    URI.create(
                        "http://127.0.0.1:"
                            + OrderIntakeMockRuntime.mockPort()
                            + "/control/listing-changed"))
                .timeout(HTTP_TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    assertThat(response.statusCode()).isEqualTo(200);
  }

  private void armFault(String method, String path, int status, int times) throws Exception {
    ObjectNode body = JSON.createObjectNode();
    body.put("method", method);
    body.put("path", path);
    body.put("status", status);
    body.put("times", times);
    HttpResponse<String> response =
        HTTP.send(
            HttpRequest.newBuilder(
                    URI.create(
                        "http://127.0.0.1:"
                            + OrderIntakeMockRuntime.mockPort()
                            + "/control/faults"))
                .timeout(HTTP_TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    assertThat(response.statusCode()).isEqualTo(200);
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

  private long stockReservationCount(StockFixture.Shop shop) {
    return fixture.inTenant(
        shop.tenant(),
        () -> jdbc.queryForObject("SELECT count(*) FROM stock_reservation", Long.class));
  }

  private long activeOrderReservations(StockFixture.Shop shop, String externalOrderId) {
    return fixture.inTenant(
        shop.tenant(),
        () ->
            jdbc.queryForObject(
                """
                SELECT count(*) FROM stock_reservation sr
                JOIN sales_order so ON so.id::text = sr.owner_ref
                WHERE so.external_order_id = ?
                  AND sr.owner_type = 'ORDER' AND sr.status = 'ACTIVE'
                """,
                Long.class,
                externalOrderId));
  }

  private Instant removedAt(StockFixture.Shop shop, UUID account, String externalSkuId) {
    return fixture.inTenant(
        shop.tenant(),
        () -> {
          List<OffsetDateTime> values =
              jdbc.query(
                  """
                  SELECT removed_at FROM channel_listing
                  WHERE channel_account_id = ? AND external_sku_id = ?
                  """,
                  (rs, row) -> rs.getObject("removed_at", OffsetDateTime.class),
                  account,
                  externalSkuId);
          if (values.isEmpty()) {
            return null;
          }
          OffsetDateTime removed = values.get(0);
          return removed == null ? null : removed.toInstant();
        });
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
    if ("shop_active".equals(shopId)) {
      SeedData seeds = OrderIntakeMockRuntime.mock().getBean(SeedData.class);
      TokenIssuer issuer = OrderIntakeMockRuntime.mock().getBean(TokenIssuer.class);
      ShopUser owner = seeds.find("owner-active").orElseThrow();
      return issuer.userAccessToken(owner);
    }
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

  private void ensureSellerSku(StockFixture.Shop shop, String sellerSku, int onHand) {
    fixture.inTenant(
        shop.tenant(),
        () -> {
          List<UUID> ids =
              jdbc.query(
                  "SELECT id FROM sku WHERE tenant_id = ? AND sku_code = ?",
                  (rs, row) -> rs.getObject("id", UUID.class),
                  shop.tenant(),
                  sellerSku);
          if (!ids.isEmpty()) {
            return;
          }
          UUID sku = UuidV7.generate();
          jdbc.update(
              "INSERT INTO sku (id, tenant_id, product_id, sku_code, name, is_bundle) "
                  + "VALUES (?, ?, ?, ?, ?, false)",
              sku,
              shop.tenant(),
              shop.product(),
              sellerSku,
              sellerSku);
          jdbc.update(
              "INSERT INTO inventory (id, tenant_id, sku_id, warehouse_id, on_hand, reserved) "
                  + "VALUES (?, ?, ?, ?, ?, 0)",
              UuidV7.generate(),
              shop.tenant(),
              sku,
              shop.warehouse(),
              onHand);
        });
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
