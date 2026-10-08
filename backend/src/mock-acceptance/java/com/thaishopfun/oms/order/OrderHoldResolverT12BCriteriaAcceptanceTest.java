package com.thaishopfun.oms.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.thaishopfun.mocktsf.MockTsfApplication;
import com.thaishopfun.mocktsf.OmsEndpoint;
import com.thaishopfun.mocktsf.SeedData;
import com.thaishopfun.mocktsf.SeedData.ShopUser;
import com.thaishopfun.mocktsf.idp.TokenIssuer;
import com.thaishopfun.oms.auth.AuthTestSupport;
import com.thaishopfun.oms.auth.UuidV7;
import com.thaishopfun.oms.inbox.InboxWorker;
import com.thaishopfun.oms.invariant.VerifyInvariants;
import com.thaishopfun.oms.order.hold.OrderHoldResolverJob;
import com.thaishopfun.oms.stock.OrderIntakeFaultTestConfig;
import com.thaishopfun.oms.stock.ReservationEngine;
import com.thaishopfun.oms.stock.StockFixture;
import com.thaishopfun.oms.stock.StockOperationException;
import com.thaishopfun.oms.stock.StockOwner;
import com.thaishopfun.oms.stock.StockRepositorySkuOmitTestConfiguration;
import com.thaishopfun.oms.stock.StockSkuLookupTestSupport;
import com.thaishopfun.oms.stock.StockTestConfig.Fault;
import com.thaishopfun.oms.stock.StockTestConfig.FaultHooks;
import com.thaishopfun.oms.tenant.TenantContext;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.Timeout.ThreadMode;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
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
@VerifyInvariants
class OrderHoldResolverT12BCriteriaAcceptanceTest {

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

  @LocalServerPort private int port;
  @Autowired InboxWorker worker;
  @Autowired JdbcTemplate jdbc;
  @Autowired PlatformTransactionManager transactions;
  @Autowired OrderHoldResolverJob resolverJob;
  @Autowired FaultHooks faults;
  @Autowired OrderStatusHistoryRepository history;
  @Autowired ReservationEngine reservationEngine;

  StockFixture fixture;
  private final ListAppender<ILoggingEvent> rootLogs = new ListAppender<>();
  private final java.util.concurrent.atomic.AtomicLong listingAggregateVersion =
      new java.util.concurrent.atomic.AtomicLong(0);

  @AfterAll
  static void stopMockTsf() {
    OrderIntakeMockRuntime.stopMock();
  }

  @AfterEach
  void teardown() {
    faults.reset();
    StockSkuLookupTestSupport.clearOmitSku();
    detachRootLogs();
    TenantContext.clear();
  }

  @BeforeEach
  void setup() throws Exception {
    listingAggregateVersion.set(0);
    OrderIntakeMockRuntime.mock().getBean(OmsEndpoint.class).setBaseUrl("http://127.0.0.1:" + port);
    fixture = new StockFixture(jdbc, transactions);
    attachRootLogs();
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
  void ac01_exactHistoryRowsAfterManualMap() throws Exception {
    StockFixture.Shop shop = fixture.shop("ACTIVE");
    String shopId = fixture.tsfShopId(shop);
    UUID account = fixture.tsfChannelAccount(shop, "ACTIVE", "CONNECTED");
    UUID sku = fixture.sku(shop, 20);
    fixture.channelListing(shop, account, "L-unmapped", null, true, false);

    String externalOrderId = "TSF-AC01-" + UUID.randomUUID();
    ingest(
        OrderIntakeScenarioSupport.orderCreated(
            JSON, externalOrderId, shopId, null, "COD", "L-unmapped", 2, 1));
    worker.processAvailable(10);

    UUID listingId = listingIdFromApi(shop, shopId, account, "L-unmapped");
    HttpResponse<String> mapResponse = httpPutMapping(userToken(shopId), listingId, sku);
    assertThat(mapResponse.statusCode()).isEqualTo(200);

    UUID orderId =
        fixture.inTenant(
            shop.tenant(),
            () ->
                jdbc.queryForObject(
                    "SELECT id FROM sales_order WHERE external_order_id = ?",
                    UUID.class,
                    externalOrderId));
    List<OrderStatusHistoryRepository.HistoryRow> rows =
        fixture.inTenant(shop.tenant(), () -> history.findByOrderId(orderId));
    assertThat(rows)
        .extracting(
            OrderStatusHistoryRepository.HistoryRow::dimension,
            OrderStatusHistoryRepository.HistoryRow::fromValue,
            OrderStatusHistoryRepository.HistoryRow::toValue,
            OrderStatusHistoryRepository.HistoryRow::reason,
            OrderStatusHistoryRepository.HistoryRow::actor)
        .containsExactly(
            tuple("HOLD", "NONE", "SKU_NOT_MAPPED", "intake", "SYSTEM"),
            tuple("HOLD", "SKU_NOT_MAPPED", "NONE", "sku mapped", "SYSTEM"),
            tuple("FULFILLMENT", "UNFULFILLED", "READY_TO_PICK", "stock ready", "SYSTEM"));
  }

  @Test
  void ac02_autoMapOnListingSync() throws Exception {
    StockFixture.Shop shop = ensureShopActive();
    String shopId = "shop_active";
    UUID account = fixture.channelAccount(shop, shopId, "ACTIVE", "CONNECTED");
    UUID sku = fixture.sku(shop, 5);
    fixture.inTenant(
        shop.tenant(),
        () -> jdbc.update("UPDATE sku SET sku_code = ? WHERE id = ?", "TSHIRT-BLK-M", sku));

    HttpResponse<String> sync =
        HTTP.send(
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
    assertThat(sync.statusCode()).isEqualTo(202);
    JsonNode syncBody = JSON.readTree(sync.body());
    assertThat(syncBody.path("auto_mapped").asInt()).isGreaterThanOrEqualTo(1);

    UUID listingId = listingIdFromApi(shop, shopId, account, "tsf_sku_7781");
    HttpResponse<String> get =
        HTTP.send(
            HttpRequest.newBuilder(
                    URI.create(
                        "http://127.0.0.1:" + port + "/api/v1/channel-listings/" + listingId))
                .timeout(HTTP_TIMEOUT)
                .header("Authorization", "Bearer " + userToken(shopId))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    assertThat(get.statusCode()).isEqualTo(200);
    assertThat(JSON.readTree(get.body()).path("mapping_source").asString()).isEqualTo("AUTO");
  }

  @Test
  void ac03_mapWithZeroStockSetsOutOfStock() throws Exception {
    StockFixture.Shop shop = fixture.shop("ACTIVE");
    String shopId = fixture.tsfShopId(shop);
    UUID account = fixture.tsfChannelAccount(shop, "ACTIVE", "CONNECTED");
    UUID sku = fixture.sku(shop, 0);
    fixture.channelListing(shop, account, "L-zero", null, true, false);

    String externalOrderId = "TSF-AC03-" + UUID.randomUUID();
    ingest(
        OrderIntakeScenarioSupport.orderCreated(
            JSON, externalOrderId, shopId, null, "COD", "L-zero", 1, 1));
    worker.processAvailable(10);

    UUID listingId = listingIdFromApi(shop, shopId, account, "L-zero");
    assertThat(httpPutMapping(userToken(shopId), listingId, sku).statusCode()).isEqualTo(200);

    String hold =
        fixture.inTenant(
            shop.tenant(),
            () ->
                jdbc.queryForObject(
                    "SELECT hold_reason FROM sales_order WHERE external_order_id = ?",
                    String.class,
                    externalOrderId));
    assertThat(hold).isEqualTo("OUT_OF_STOCK");
  }

  @Test
  void ac04_mappingNoOpDoesNotDuplicateAudit() throws Exception {
    StockFixture.Shop shop = fixture.shop("ACTIVE");
    String shopId = fixture.tsfShopId(shop);
    UUID account = fixture.tsfChannelAccount(shop, "ACTIVE", "CONNECTED");
    UUID sku = fixture.sku(shop, 4);
    fixture.channelListing(shop, account, "L-noop", null, true, false);

    UUID listingId = listingIdFromApi(shop, shopId, account, "L-noop");
    String token = userToken(shopId);
    assertThat(httpPutMapping(token, listingId, sku).statusCode()).isEqualTo(200);
    long mappedAudits =
        fixture.inTenant(
            shop.tenant(),
            () ->
                jdbc.queryForObject(
                    "SELECT count(*) FROM audit_log WHERE action = 'CHANNEL_LISTING_MAPPED'",
                    Long.class));
    assertThat(mappedAudits).isEqualTo(1);

    HttpResponse<String> second = httpPutMapping(token, listingId, sku);
    assertThat(second.statusCode()).isEqualTo(200);
    assertThat(
            fixture.inTenant(
                shop.tenant(),
                () ->
                    jdbc.queryForObject(
                        "SELECT count(*) FROM audit_log WHERE action = 'CHANNEL_LISTING_MAPPED'",
                        Long.class)))
        .isEqualTo(1);
    JsonNode reeval = JSON.readTree(second.body()).path("reevaluation");
    assertThat(reeval.isObject()).isTrue();
    assertThat(reeval.path("released").asInt()).isZero();
    assertThat(reeval.path("out_of_stock").asInt()).isZero();
    assertThat(reeval.path("still_held").asInt()).isZero();
    assertThat(reeval.path("deferred").asInt()).isZero();
  }

  @Test
  void ac05_deleteMappingClearsSku() throws Exception {
    StockFixture.Shop shop = fixture.shop("ACTIVE");
    String shopId = fixture.tsfShopId(shop);
    UUID account = fixture.tsfChannelAccount(shop, "ACTIVE", "CONNECTED");
    UUID sku = fixture.sku(shop, 3);
    fixture.channelListing(shop, account, "L-delmap", sku, true);

    UUID listingId = listingIdFromApi(shop, shopId, account, "L-delmap");
    HttpResponse<String> deleted =
        HTTP.send(
            HttpRequest.newBuilder(
                    URI.create(
                        "http://127.0.0.1:"
                            + port
                            + "/api/v1/channel-listings/"
                            + listingId
                            + "/mapping"))
                .timeout(HTTP_TIMEOUT)
                .header("Authorization", "Bearer " + userToken(shopId))
                .DELETE()
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    assertThat(deleted.statusCode()).isEqualTo(200);
    assertThat(JSON.readTree(deleted.body()).path("sku_id").isNull()).isTrue();
  }

  @Test
  void ac06_sweeperOnlySkuNotMappedNotOutOfStock() throws Exception {
    StockFixture.Shop shop = fixture.shop("ACTIVE");
    String shopId = fixture.tsfShopId(shop);
    UUID account = fixture.tsfChannelAccount(shop, "ACTIVE", "CONNECTED");
    UUID oosSku = fixture.sku(shop, 0);
    fixture.channelListing(shop, account, "L-oos-sw", oosSku, true);

    String oosExternal = "TSF-AC06-OOS-" + UUID.randomUUID();
    ingest(
        OrderIntakeScenarioSupport.orderCreated(
            JSON, oosExternal, shopId, null, "COD", "L-oos-sw", 1, 1));
    worker.processAvailable(10);
    fixture.receive(shop, oosSku, 10);
    resolverJob.runScheduledBatch();
    assertThat(holdReason(shop, oosExternal)).isEqualTo("OUT_OF_STOCK");

    UUID mapSku = fixture.sku(shop, 8);
    fixture.channelListing(shop, account, "L-snm-sw", null, true, false);
    String snmExternal = "TSF-AC06-SNM-" + UUID.randomUUID();
    ingest(
        OrderIntakeScenarioSupport.orderCreated(
            JSON, snmExternal, shopId, null, "COD", "L-snm-sw", 1, 1));
    worker.processAvailable(10);
    assertThat(holdReason(shop, snmExternal)).isEqualTo("SKU_NOT_MAPPED");

    fixture.inTenant(
        shop.tenant(),
        () ->
            jdbc.update(
                """
                UPDATE channel_listing
                SET sku_id = ?, mapping_source = 'MANUAL', mapped_at = now()
                WHERE channel_account_id = ? AND external_sku_id = 'L-snm-sw'
                """,
                mapSku,
                account));
    faults.failNext(Fault.THROW);
    resolverJob.runScheduledBatch();
    assertThat(holdReason(shop, snmExternal)).isEqualTo("SKU_NOT_MAPPED");
    assertThat(faults.fired()).isGreaterThanOrEqualTo(1);

    faults.reset();
    resolverJob.runScheduledBatch();
    assertThat(holdReason(shop, snmExternal)).isEqualTo("NONE");
  }

  @Test
  void ac07_holdRecheckConcurrentIdempotency() throws Exception {
    StockFixture.Shop shop = fixture.shop("ACTIVE");
    String shopId = fixture.tsfShopId(shop);
    UUID account = fixture.tsfChannelAccount(shop, "ACTIVE", "CONNECTED");
    UUID sku = fixture.sku(shop, 0);
    fixture.channelListing(shop, account, "L-conc", sku, true);

    String externalOrderId = "TSF-AC07-" + UUID.randomUUID();
    ingest(
        OrderIntakeScenarioSupport.orderCreated(
            JSON, externalOrderId, shopId, null, "COD", "L-conc", 1, 1));
    worker.processAvailable(10);
    UUID orderId =
        fixture.inTenant(
            shop.tenant(),
            () ->
                jdbc.queryForObject(
                    "SELECT id FROM sales_order WHERE external_order_id = ?",
                    UUID.class,
                    externalOrderId));

    String token = userToken(shopId);
    String key = "recheck-conc-" + UuidV7.generate();
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(5);
    try {
      List<Future<HttpResponse<String>>> futures = new ArrayList<>();
      for (int i = 0; i < 5; i++) {
        futures.add(
            pool.submit(
                () -> {
                  start.await(10, TimeUnit.SECONDS);
                  return httpHoldRecheck(token, orderId, key);
                }));
      }
      start.countDown();
      List<Integer> statuses = new ArrayList<>();
      List<String> bodies = new ArrayList<>();
      for (Future<HttpResponse<String>> future : futures) {
        HttpResponse<String> response = future.get(30, TimeUnit.SECONDS);
        int status = response.statusCode();
        assertThat(status).isIn(200, 409);
        statuses.add(status);
        if (status == 200) {
          bodies.add(response.body());
        } else {
          assertThat(JSON.readTree(response.body()).path("error").asString())
              .isEqualTo("IDEMPOTENCY_IN_PROGRESS");
        }
      }
      assertThat(statuses).contains(200);
      assertThat(bodies).isNotEmpty();
      assertThat(bodies).allMatch(body -> body.equals(bodies.get(0)));
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void ac08_listingChangedDoesNotCallEngine() throws Exception {
    StockFixture.Shop shop = fixture.shop("ACTIVE");
    String shopId = fixture.tsfShopId(shop);
    fixture.tsfChannelAccount(shop, "ACTIVE", "CONNECTED");
    long before = ensureHoldKeyCount(shop);

    listingChanged(shopId, "L-changed", "UPSERT", "SKU-X", "Changed");
    worker.processAvailable(10);
    listingChanged(shopId, "L-changed", "DELETE", "SKU-X", null);
    worker.processAvailable(10);

    long after = ensureHoldKeyCount(shop);
    assertThat(after).isEqualTo(before);
  }

  @Test
  void ac09_syncFetchesAcrossTwoPages() throws Exception {
    StockFixture.Shop shop = ensureShopActive();
    String shopId = "shop_active";
    UUID account = fixture.channelAccount(shop, shopId, "ACTIVE", "CONNECTED");

    HttpResponse<String> sync =
        HTTP.send(
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
    assertThat(sync.statusCode()).isEqualTo(202);
    JsonNode body = JSON.readTree(sync.body());
    assertThat(body.path("fetched").asInt()).isGreaterThanOrEqualTo(2);
    assertThat(body.path("created").asInt() + body.path("updated").asInt())
        .isGreaterThanOrEqualTo(1);
  }

  @Test
  void ac10_terminalOrderHoldRecheckRejected() throws Exception {
    StockFixture.Shop shop = fixture.shop("ACTIVE");
    String shopId = fixture.tsfShopId(shop);
    UUID account = fixture.tsfChannelAccount(shop, "ACTIVE", "CONNECTED");
    UUID sku = fixture.sku(shop, 2);
    fixture.channelListing(shop, account, "L-term", sku, true);

    String externalOrderId = "TSF-AC10-" + UUID.randomUUID();
    ingest(
        OrderIntakeScenarioSupport.orderCreated(
            JSON, externalOrderId, shopId, null, "COD", "L-term", 1, 1));
    worker.processAvailable(10);
    ingest(OrderIntakeScenarioSupport.orderCancelled(JSON, externalOrderId, shopId, 2));
    worker.processAvailable(10);

    UUID orderId =
        fixture.inTenant(
            shop.tenant(),
            () ->
                jdbc.queryForObject(
                    "SELECT id FROM sales_order WHERE external_order_id = ?",
                    UUID.class,
                    externalOrderId));
    HttpResponse<String> response =
        httpHoldRecheck(userToken(shopId), orderId, "recheck-term-" + UuidV7.generate());
    assertThat(response.statusCode()).isEqualTo(409);
    assertThat(JSON.readTree(response.body()).path("error").asString())
        .isEqualTo("HOLD_NOT_RECHECKABLE");
  }

  @Test
  void ac11_sweeperFaultThenRetry() throws Exception {
    StockFixture.Shop shop = fixture.shop("ACTIVE");
    String shopId = fixture.tsfShopId(shop);
    UUID account = fixture.tsfChannelAccount(shop, "ACTIVE", "CONNECTED");
    UUID sku = fixture.sku(shop, 20);
    fixture.channelListing(shop, account, "L-sweep-http", null, true, false);

    String externalOrderId = "TSF-AC11-" + UUID.randomUUID();
    ingest(
        OrderIntakeScenarioSupport.orderCreated(
            JSON, externalOrderId, shopId, null, "COD", "L-sweep-http", 1, 1));
    worker.processAvailable(10);

    fixture.inTenant(
        shop.tenant(),
        () ->
            jdbc.update(
                """
                UPDATE channel_listing
                SET sku_id = ?, mapping_source = 'MANUAL', mapped_at = now()
                WHERE channel_account_id = ? AND external_sku_id = 'L-sweep-http'
                """,
                sku,
                account));
    faults.failNext(Fault.THROW);
    resolverJob.runScheduledBatch();
    assertThat(holdReason(shop, externalOrderId)).isEqualTo("SKU_NOT_MAPPED");
    assertThat(faults.fired()).isGreaterThanOrEqualTo(1);

    faults.reset();
    resolverJob.runScheduledBatch();
    assertThat(holdReason(shop, externalOrderId)).isEqualTo("NONE");
  }

  @Test
  void ac12_holdRecheck400MissingKey403Staff403Grace422Conflict() throws Exception {
    StockFixture.Shop shop = fixture.shop("ACTIVE");
    String shopId = fixture.tsfShopId(shop);
    UUID account = fixture.tsfChannelAccount(shop, "ACTIVE", "CONNECTED");
    UUID sku = fixture.sku(shop, 0);
    fixture.channelListing(shop, account, "L-ac12", sku, true);

    String externalOrderId = "TSF-AC12-" + UUID.randomUUID();
    ingest(
        OrderIntakeScenarioSupport.orderCreated(
            JSON, externalOrderId, shopId, null, "COD", "L-ac12", 1, 1));
    worker.processAvailable(10);
    UUID orderId =
        fixture.inTenant(
            shop.tenant(),
            () ->
                jdbc.queryForObject(
                    "SELECT id FROM sales_order WHERE external_order_id = ?",
                    UUID.class,
                    externalOrderId));

    HttpResponse<String> missingKey =
        HTTP.send(
            HttpRequest.newBuilder(
                    URI.create(
                        "http://127.0.0.1:"
                            + port
                            + "/api/v1/orders/"
                            + orderId
                            + "/hold-rechecks"))
                .timeout(HTTP_TIMEOUT)
                .header("Authorization", "Bearer " + userToken(shopId))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    assertThat(missingKey.statusCode()).isEqualTo(400);

    assertThat(httpHoldRecheck(staffToken(shopId), orderId, "staff-key").statusCode())
        .isEqualTo(403);
    fixture.inTenant(
        shop.tenant(),
        () ->
            jdbc.update(
                "UPDATE tenant SET entitlement_status = 'GRACE' WHERE id = ?", shop.tenant()));
    HttpResponse<String> graceRecheck = httpHoldRecheck(userToken(shopId), orderId, "grace-key");
    assertThat(graceRecheck.statusCode()).isEqualTo(403);
    assertThat(JSON.readTree(graceRecheck.body()).path("error").asString())
        .isEqualTo("ENTITLEMENT_GRACE");
    fixture.inTenant(
        shop.tenant(),
        () ->
            jdbc.update(
                "UPDATE tenant SET entitlement_status = 'ACTIVE' WHERE id = ?", shop.tenant()));

    String keyA = "oos-a-" + UuidV7.generate();
    String keyB = "oos-b-" + UuidV7.generate();
    assertThat(httpHoldRecheck(userToken(shopId), orderId, keyA).statusCode()).isEqualTo(200);
    assertThat(httpHoldRecheck(userToken(shopId), orderId, keyB).statusCode()).isEqualTo(200);

    String clientConflictKey = "conflict-" + UuidV7.generate();
    assertThat(httpHoldRecheck(userToken(shopId), orderId, clientConflictKey).statusCode())
        .isEqualTo(200);
    String storageKey = orderId + ":" + clientConflictKey;
    try (Connection admin = AuthTestSupport.admin();
        var statement =
            admin.prepareStatement(
                """
                UPDATE idempotency_key SET request_hash = ?
                WHERE tenant_id = ? AND scope = 'order.hold_recheck' AND "key" = ?
                """)) {
      statement.setString(1, "deadbeef");
      statement.setObject(2, shop.tenant());
      statement.setString(3, storageKey);
      assertThat(statement.executeUpdate()).isEqualTo(1);
    }
    HttpResponse<String> conflict = httpHoldRecheck(userToken(shopId), orderId, clientConflictKey);
    assertThat(conflict.statusCode()).isEqualTo(422);
    assertThat(JSON.readTree(conflict.body()).path("error").asString())
        .isEqualTo("IDEMPOTENCY_KEY_REUSED");
  }

  @Test
  void ac13_listingApiValidation() throws Exception {
    StockFixture.Shop shop = fixture.shop("ACTIVE");
    String shopId = fixture.tsfShopId(shop);
    String token = userToken(shopId);

    HttpResponse<String> missingAccount =
        HTTP.send(
            HttpRequest.newBuilder(
                    URI.create("http://127.0.0.1:" + port + "/api/v1/channel-listings"))
                .timeout(HTTP_TIMEOUT)
                .header("Authorization", "Bearer " + token)
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    assertThat(missingAccount.statusCode()).isEqualTo(422);

    UUID randomListing = UuidV7.generate();
    HttpResponse<String> missingListing =
        HTTP.send(
            HttpRequest.newBuilder(
                    URI.create(
                        "http://127.0.0.1:" + port + "/api/v1/channel-listings/" + randomListing))
                .timeout(HTTP_TIMEOUT)
                .header("Authorization", "Bearer " + token)
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    assertThat(missingListing.statusCode()).isEqualTo(404);

    UUID randomAccount = UuidV7.generate();
    HttpResponse<String> missingSync =
        HTTP.send(
            HttpRequest.newBuilder(
                    URI.create(
                        "http://127.0.0.1:"
                            + port
                            + "/api/v1/channel-accounts/"
                            + randomAccount
                            + "/listing-syncs"))
                .timeout(HTTP_TIMEOUT)
                .header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    assertThat(missingSync.statusCode()).isEqualTo(404);
  }

  @Test
  void ac14_syncChannelError503Unavailable() throws Exception {
    StockFixture.Shop shop = ensureShopActive();
    String shopId = "shop_active";
    UUID account = fixture.channelAccount(shop, shopId, "ACTIVE", "CONNECTED");
    armFault("GET", "/internal/v1/shops/shop_active/listings", 503, 1);

    HttpResponse<String> sync =
        HTTP.send(
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
    assertThat(sync.statusCode()).isEqualTo(503);
    assertThat(JSON.readTree(sync.body()).path("error").asString())
        .isEqualTo("CHANNEL_UNAVAILABLE");
  }

  @Test
  void acD2_replayUnknownSkuIncludesSkuId() throws Exception {
    StockFixture.Shop shop = fixture.shop("ACTIVE");
    String shopId = fixture.tsfShopId(shop);
    UUID account = fixture.tsfChannelAccount(shop, "ACTIVE", "CONNECTED");
    UUID sku = fixture.sku(shop, 6);
    fixture.channelListing(shop, account, "L-unk-recheck", sku, true);
    StockSkuLookupTestSupport.omitSkuFromCatalogLookup(sku);

    String externalOrderId = "TSF-ACD2-" + UUID.randomUUID();
    ingest(
        OrderIntakeScenarioSupport.orderCreated(
            JSON, externalOrderId, shopId, null, "COD", "L-unk-recheck", 1, 1));
    worker.processAvailable(10);
    UUID orderId =
        fixture.inTenant(
            shop.tenant(),
            () ->
                jdbc.queryForObject(
                    "SELECT id FROM sales_order WHERE external_order_id = ?",
                    UUID.class,
                    externalOrderId));
    assertThat(holdReason(shop, externalOrderId)).isEqualTo("SKU_NOT_MAPPED");

    String clientKey = "unk-recheck-" + UuidV7.generate();
    assertThat(httpHoldRecheck(userToken(shopId), orderId, clientKey).statusCode()).isEqualTo(200);

    String engineKey =
        fixture.inTenant(
            shop.tenant(),
            () ->
                jdbc.queryForObject(
                    """
                    SELECT "key" FROM idempotency_key
                    WHERE scope = 'stock.ensure_order_hold'
                    ORDER BY created_at DESC LIMIT 1
                    """,
                    String.class));

    StockSkuLookupTestSupport.clearOmitSku();
    Throwable replay =
        catchThrowable(
            () ->
                fixture.inTenant(
                    shop.tenant(),
                    () ->
                        reservationEngine.ensureOrderHold(
                            StockOwner.order(orderId.toString()),
                            List.of(com.thaishopfun.oms.stock.ReserveItem.of(sku, 1)),
                            engineKey)));
    assertThat(replay).isInstanceOf(StockOperationException.class);
    assertThat(((StockOperationException) replay).skuId()).isEqualTo(sku);
  }

  @Test
  void acPii_logsNoRecipientDataOnMapping() throws Exception {
    StockFixture.Shop shop = fixture.shop("ACTIVE");
    String shopId = fixture.tsfShopId(shop);
    UUID account = fixture.tsfChannelAccount(shop, "ACTIVE", "CONNECTED");
    UUID sku = fixture.sku(shop, 5);
    fixture.channelListing(shop, account, "tsf_sku_7781", null, true, false);

    String externalOrderId = "TSF-PII-" + UUID.randomUUID();
    ObjectNode created = loadExample("order.created.json");
    created.put("event_id", "evt-" + UUID.randomUUID());
    created.put("tsf_shop_id", shopId);
    created.put("aggregate_id", externalOrderId);
    created.put("aggregate_version", 1);
    created.put("occurred_at", Instant.now().truncatedTo(ChronoUnit.SECONDS).toString());
    ObjectNode data = (ObjectNode) created.get("data");
    data.put("order_id", externalOrderId);
    data.put("reservation_id", UuidV7.generate().toString());
    data.put("payment_method", "COD");
    ingest(created);
    worker.processAvailable(10);

    rootLogs.list.clear();
    UUID listingId = listingIdFromApi(shop, shopId, account, "tsf_sku_7781");
    assertThat(httpPutMapping(userToken(shopId), listingId, sku).statusCode()).isEqualTo(200);

    String logBlob =
        rootLogs.list.stream()
            .map(ILoggingEvent::getFormattedMessage)
            .reduce("", (a, b) -> a + "\n" + b);
    assertThat(logBlob).doesNotContain("0812341234");
    assertThat(logBlob).doesNotContain("สมชาย");
  }

  @Test
  void acHoldRecheckRetryAfterFaultSameKey() throws Exception {
    StockFixture.Shop shop = fixture.shop("ACTIVE");
    String shopId = fixture.tsfShopId(shop);
    UUID account = fixture.tsfChannelAccount(shop, "ACTIVE", "CONNECTED");
    UUID sku = fixture.sku(shop, 0);
    fixture.channelListing(shop, account, "L-fault-recheck", sku, true);

    String externalOrderId = "TSF-HRF-" + UUID.randomUUID();
    ingest(
        OrderIntakeScenarioSupport.orderCreated(
            JSON, externalOrderId, shopId, null, "COD", "L-fault-recheck", 1, 1));
    worker.processAvailable(10);
    UUID orderId =
        fixture.inTenant(
            shop.tenant(),
            () ->
                jdbc.queryForObject(
                    "SELECT id FROM sales_order WHERE external_order_id = ?",
                    UUID.class,
                    externalOrderId));

    String key = "fault-recheck-" + UuidV7.generate();
    faults.failNext(Fault.THROW);
    HttpResponse<String> first = httpHoldRecheck(userToken(shopId), orderId, key);
    assertThat(first.statusCode()).isGreaterThanOrEqualTo(500);

    faults.reset();
    HttpResponse<String> second = httpHoldRecheck(userToken(shopId), orderId, key);
    assertThat(second.statusCode()).isEqualTo(200);
    assertThat(second.statusCode()).isNotEqualTo(500);
  }

  @Test
  void acCancelRaceTwentyParallel() throws Exception {
    StockFixture.Shop shop = fixture.shop("ACTIVE");
    String shopId = fixture.tsfShopId(shop);
    UUID account = fixture.tsfChannelAccount(shop, "ACTIVE", "CONNECTED");
    UUID sku = fixture.sku(shop, 3);
    fixture.channelListing(shop, account, "L-cancel-race", sku, true);

    String externalOrderId = "TSF-CAN-RACE-" + UUID.randomUUID();
    registerMockOrder(externalOrderId);
    ingest(
        OrderIntakeScenarioSupport.orderCreated(
            JSON, externalOrderId, shopId, null, "COD", "L-cancel-race", 1, 1));
    worker.processAvailable(10);
    UUID orderId =
        fixture.inTenant(
            shop.tenant(),
            () ->
                jdbc.queryForObject(
                    "SELECT id FROM sales_order WHERE external_order_id = ?",
                    UUID.class,
                    externalOrderId));

    String token = userToken(shopId);
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(20);
    AtomicInteger accepted = new AtomicInteger();
    try {
      List<Future<?>> futures = new ArrayList<>();
      for (int i = 0; i < 20; i++) {
        futures.add(
            pool.submit(
                () -> {
                  start.await(15, TimeUnit.SECONDS);
                  HttpResponse<String> response =
                      HTTP.send(
                          HttpRequest.newBuilder(
                                  URI.create(
                                      "http://127.0.0.1:"
                                          + port
                                          + "/api/v1/orders/"
                                          + orderId
                                          + "/cancel-requests"))
                              .timeout(HTTP_TIMEOUT)
                              .header("Authorization", "Bearer " + token)
                              .header("Content-Type", "application/json")
                              .POST(
                                  HttpRequest.BodyPublishers.ofString(
                                      "{\"reason\":\"buyer asked\"}", StandardCharsets.UTF_8))
                              .build(),
                          HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                  if (response.statusCode() == 202) {
                    accepted.incrementAndGet();
                  }
                  return response.statusCode();
                }));
      }
      start.countDown();
      for (Future<?> future : futures) {
        assertThat(future.get(60, TimeUnit.SECONDS)).isIn(202, 409);
      }
      assertThat(accepted.get()).isGreaterThanOrEqualTo(1);
    } finally {
      pool.shutdownNow();
    }

    assertThat(
            fixture.inTenant(
                shop.tenant(),
                () ->
                    jdbc.queryForObject(
                        """
                        SELECT count(*) FROM audit_log
                        WHERE action = 'ORDER_CANCEL_REQUESTED'
                        """,
                        Long.class)))
        .isEqualTo(1);
  }

  private static org.assertj.core.groups.Tuple tuple(
      String dimension, String from, String to, String reason, String actor) {
    return org.assertj.core.groups.Tuple.tuple(dimension, from, to, reason, actor);
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
    for (JsonNode item : JSON.readTree(list.body()).path("items")) {
      if (externalSkuId.equals(item.path("external_sku_id").asString())) {
        return UUID.fromString(item.path("id").asString());
      }
    }
    throw new AssertionError("listing not found via API: " + externalSkuId);
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

  private long ensureHoldKeyCount(StockFixture.Shop shop) {
    return fixture.inTenant(
        shop.tenant(),
        () ->
            jdbc.queryForObject(
                "SELECT count(*) FROM idempotency_key WHERE scope = 'stock.ensure_order_hold'",
                Long.class));
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

  private void registerMockOrder(String externalOrderId) throws Exception {
    HttpResponse<String> response =
        HTTP.send(
            HttpRequest.newBuilder(
                    URI.create(
                        "http://127.0.0.1:"
                            + OrderIntakeMockRuntime.mockPort()
                            + "/control/rest/demo-order/"
                            + externalOrderId))
                .timeout(HTTP_TIMEOUT)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    assertThat(response.statusCode()).isEqualTo(200);
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
    return memberToken(shopId, "OWNER", "ACTIVE");
  }

  private String staffToken(String shopId) throws Exception {
    return memberToken(shopId, "STAFF", "ACTIVE");
  }

  private String graceOwnerToken(String shopId) throws Exception {
    return memberToken(shopId, "OWNER", "GRACE");
  }

  private String memberToken(String shopId, String role, String entitlement) throws Exception {
    TokenIssuer issuer = OrderIntakeMockRuntime.mock().getBean(TokenIssuer.class);
    SeedData.ShopUser user =
        new SeedData.ShopUser(
            role.toLowerCase() + "-" + UuidV7.generate(),
            role.toLowerCase() + "@test.local",
            shopId,
            "Test Shop",
            role,
            "PRO",
            entitlement,
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

  private static ObjectNode loadExample(String name) throws IOException {
    try (InputStream in =
        MockTsfApplication.class.getResourceAsStream("/contracts/examples/events/" + name)) {
      if (in == null) {
        throw new IllegalStateException("missing example " + name);
      }
      return (ObjectNode) JSON.readTree(in);
    }
  }

  private void attachRootLogs() {
    rootLogs.start();
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    root.addAppender(rootLogs);
  }

  private void detachRootLogs() {
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    root.detachAppender(rootLogs);
    rootLogs.list.clear();
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
