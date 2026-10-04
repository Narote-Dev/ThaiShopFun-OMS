package com.thaishopfun.oms.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.thaishopfun.mocktsf.OmsEndpoint;
import com.thaishopfun.mocktsf.SeedData;
import com.thaishopfun.mocktsf.SeedData.ShopUser;
import com.thaishopfun.mocktsf.idp.TokenIssuer;
import com.thaishopfun.oms.auth.AuthTestSupport;
import com.thaishopfun.oms.auth.UuidV7;
import com.thaishopfun.oms.inbox.InboxWorker;
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
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
class OrderHoldResolverT12BRound3AcceptanceTest {

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

  StockFixture fixture;
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
              + "idempotency_key, reconciliation_issue, shadow_diff, channel_listing CASCADE");
      statement.execute("SET session_replication_role = DEFAULT");
    }
  }

  @Test
  void holdRecheckReplayAfterReleaseSameKey() throws Exception {
    StockFixture.Shop shop = fixture.shop("ACTIVE");
    String shopId = fixture.tsfShopId(shop);
    UUID account = fixture.tsfChannelAccount(shop, "ACTIVE", "CONNECTED");
    UUID sku = fixture.sku(shop, 0);
    fixture.channelListing(shop, account, "L-replay-oos", sku, true);

    String externalOrderId = "TSF-R3-6A-" + UUID.randomUUID();
    ingest(
        OrderIntakeScenarioSupport.orderCreated(
            JSON, externalOrderId, shopId, null, "COD", "L-replay-oos", 1, 1));
    worker.processAvailable(10);
    UUID orderId = orderId(shop, externalOrderId);
    assertThat(holdReason(shop, externalOrderId)).isEqualTo("OUT_OF_STOCK");

    fixture.receive(shop, sku, 5);
    String key = "replay-release-" + UuidV7.generate();
    HttpResponse<String> first = httpHoldRecheck(userToken(shopId), orderId, key);
    assertThat(first.statusCode()).isEqualTo(200);
    JsonNode firstBody = JSON.readTree(first.body());
    assertThat(firstBody.path("hold_reason").asString()).isEqualTo("NONE");
    assertThat(fulfillmentStatus(shop, externalOrderId)).isEqualTo("READY_TO_PICK");
    long historyAfterFirst = historyCount(shop, orderId);

    HttpResponse<String> second = httpHoldRecheck(userToken(shopId), orderId, key);
    assertThat(second.statusCode()).isEqualTo(200);
    assertThat(second.body()).isEqualTo(first.body());
    assertThat(auditCount(shop, "ORDER_HOLD_RECHECKED", orderId)).isEqualTo(1);
    assertThat(activeOrderReservations(shop, externalOrderId)).isEqualTo(1);
    assertThat(historyCount(shop, orderId)).isEqualTo(historyAfterFirst);
  }

  @Test
  void mappingPutVsOrderCancelledRace() throws Exception {
    for (int iteration = 0; iteration < 20; iteration++) {
      StockFixture.Shop shop = fixture.shop("ACTIVE");
      String shopId = fixture.tsfShopId(shop);
      UUID account = fixture.tsfChannelAccount(shop, "ACTIVE", "CONNECTED");
      UUID sku = fixture.sku(shop, 8);
      fixture.channelListing(shop, account, "L-race-" + iteration, null, true, false);

      String externalOrderId = "TSF-R3-6B-" + iteration + "-" + UUID.randomUUID();
      ingest(
          OrderIntakeScenarioSupport.orderCreated(
              JSON, externalOrderId, shopId, null, "COD", "L-race-" + iteration, 1, 1));
      worker.processAvailable(10);
      assertThat(holdReason(shop, externalOrderId)).isEqualTo("SKU_NOT_MAPPED");

      UUID listingId = listingIdFromApi(shop, shopId, account, "L-race-" + iteration);
      String token = userToken(shopId);
      CountDownLatch start = new CountDownLatch(1);
      ExecutorService pool = Executors.newFixedThreadPool(2);
      try {
        Future<?> put =
            pool.submit(
                () -> {
                  start.await(15, TimeUnit.SECONDS);
                  return httpPutMapping(token, listingId, sku);
                });
        Future<?> cancel =
            pool.submit(
                () -> {
                  start.await(15, TimeUnit.SECONDS);
                  ingest(
                      OrderIntakeScenarioSupport.orderCancelled(JSON, externalOrderId, shopId, 2));
                  worker.processAvailable(10);
                  return null;
                });
        start.countDown();
        put.get(60, TimeUnit.SECONDS);
        cancel.get(60, TimeUnit.SECONDS);
      } finally {
        pool.shutdownNow();
      }

      assertThat(activeOrderReservations(shop, externalOrderId)).isZero();
      assertThat(orderStatus(shop, externalOrderId)).isEqualTo("CANCELLED");
    }
  }

  @Test
  void concurrentMappingReevalSingleReservation() throws Exception {
    for (int threads = 2; threads <= 5; threads++) {
      StockFixture.Shop shop = fixture.shop("ACTIVE");
      String shopId = fixture.tsfShopId(shop);
      UUID account = fixture.tsfChannelAccount(shop, "ACTIVE", "CONNECTED");
      UUID sku = fixture.sku(shop, 12);
      String listingSku = "L-conc-reeval-" + threads + "-" + UUID.randomUUID();
      fixture.channelListing(shop, account, listingSku, null, true, false);

      String externalOrderId = "TSF-R3-6C-" + threads + "-" + UUID.randomUUID();
      ingest(
          OrderIntakeScenarioSupport.orderCreated(
              JSON, externalOrderId, shopId, null, "COD", listingSku, 1, 1));
      worker.processAvailable(10);
      UUID orderId = orderId(shop, externalOrderId);
      UUID listingId = listingIdFromApi(shop, shopId, account, listingSku);
      String token = userToken(shopId);

      CountDownLatch start = new CountDownLatch(1);
      ExecutorService pool = Executors.newFixedThreadPool(threads);
      try {
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
          final boolean usePut = i % 2 == 0;
          futures.add(
              pool.submit(
                  () -> {
                    start.await(15, TimeUnit.SECONDS);
                    if (usePut) {
                      httpPutMapping(token, listingId, sku);
                    } else {
                      resolverJob.runScheduledBatch();
                    }
                    return null;
                  }));
        }
        start.countDown();
        for (Future<?> future : futures) {
          future.get(60, TimeUnit.SECONDS);
        }
      } finally {
        pool.shutdownNow();
      }

      assertThat(activeOrderReservations(shop, externalOrderId)).isEqualTo(1);
      assertThat(reservedLedgerRows(shop, orderId)).isEqualTo(1);
      assertThat(fulfillmentReadyCount(shop, orderId)).isEqualTo(1);
      fixture.assertInvariants(shop);
    }
  }

  @Test
  void listingChangedAutoMapSweeperDeleteDuplicateNoOp() throws Exception {
    StockFixture.Shop shop = fixture.shop("ACTIVE");
    String shopId = fixture.tsfShopId(shop);
    UUID account = fixture.tsfChannelAccount(shop, "ACTIVE", "CONNECTED");
    UUID sku = fixture.sku(shop, 6);
    fixture.inTenant(
        shop.tenant(),
        () -> jdbc.update("UPDATE sku SET sku_code = ? WHERE id = ?", "R3-LC-MATCH", sku));
    fixture.channelListing(shop, account, "L-r3-lc", null, true, false);

    String externalOrderId = "TSF-R3-6D-" + UUID.randomUUID();
    ingest(
        OrderIntakeScenarioSupport.orderCreated(
            JSON, externalOrderId, shopId, null, "COD", "L-r3-lc", 1, 1));
    worker.processAvailable(10);
    assertThat(holdReason(shop, externalOrderId)).isEqualTo("SKU_NOT_MAPPED");

    long keysBefore = ensureHoldKeyCount(shop);
    listingChanged(shopId, "L-r3-lc", "UPSERT", "R3-LC-MATCH", "Renamed");
    worker.processAvailable(10);
    assertThat(ensureHoldKeyCount(shop)).isEqualTo(keysBefore);
    assertThat(mappingSource(shop, account, "L-r3-lc")).isEqualTo("AUTO");
    assertThat(skuIdOnListing(shop, account, "L-r3-lc")).isEqualTo(sku);

    resolverJob.runScheduledBatch();
    assertThat(holdReason(shop, externalOrderId)).isEqualTo("NONE");
    assertThat(activeOrderReservations(shop, externalOrderId)).isEqualTo(1);

    listingChanged(shopId, "L-r3-lc", "DELETE", "R3-LC-MATCH", null);
    worker.processAvailable(10);
    assertThat(removedAt(shop, account, "L-r3-lc")).isNotNull();
    assertThat(skuIdOnListing(shop, account, "L-r3-lc")).isEqualTo(sku);

    long auditsBefore =
        fixture.inTenant(
            shop.tenant(),
            () ->
                jdbc.queryForObject(
                    "SELECT count(*) FROM audit_log WHERE action = 'CHANNEL_LISTING_MAPPED'",
                    Long.class));
    listingChanged(shopId, "L-r3-lc", "UPSERT", "R3-LC-MATCH", "Renamed again");
    worker.processAvailable(10);
    listingChanged(shopId, "L-r3-lc", "UPSERT", "R3-LC-MATCH", "Renamed again");
    worker.processAvailable(10);
    assertThat(
            fixture.inTenant(
                shop.tenant(),
                () ->
                    jdbc.queryForObject(
                        "SELECT count(*) FROM audit_log WHERE action = 'CHANNEL_LISTING_MAPPED'",
                        Long.class)))
        .isEqualTo(auditsBefore);
    assertThat(skuIdOnListing(shop, account, "L-r3-lc")).isEqualTo(sku);
  }

  @Test
  void syncTwoPagesPreservesManualMapping() throws Exception {
    StockFixture.Shop shop = ensureShopActive();
    String shopId = "shop_active";
    UUID account = fixture.channelAccount(shop, shopId, "ACTIVE", "CONNECTED");
    UUID manualSku = fixture.sku(shop, 3);

    HttpResponse<String> firstSync = postListingSync(shopId, account);
    assertThat(firstSync.statusCode()).isEqualTo(202);
    assertThat(JSON.readTree(firstSync.body()).path("fetched").asInt()).isGreaterThanOrEqualTo(2);

    UUID listingId = listingIdFromApi(shop, shopId, account, "tsf_sku_9001");
    assertThat(httpPutMapping(userToken(shopId), listingId, manualSku).statusCode()).isEqualTo(200);

    HttpResponse<String> secondSync = postListingSync(shopId, account);
    assertThat(secondSync.statusCode()).isEqualTo(202);
    assertThat(JSON.readTree(secondSync.body()).path("fetched").asInt()).isGreaterThanOrEqualTo(2);

    assertThat(sellerSku(shop, account, "tsf_sku_9001")).isEqualTo("MUG-WHT");
    assertThat(listingName(shop, account, "tsf_sku_9001")).isEqualTo("แก้วขาว");
    assertThat(mappingSource(shop, account, "tsf_sku_9001")).isEqualTo("MANUAL");
    assertThat(skuIdOnListing(shop, account, "tsf_sku_9001")).isEqualTo(manualSku);

    long rowCount =
        fixture.inTenant(
            shop.tenant(),
            () ->
                jdbc.queryForObject(
                    "SELECT count(*) FROM channel_listing WHERE channel_account_id = ?",
                    Long.class,
                    account));
    HttpResponse<String> thirdSync = postListingSync(shopId, account);
    assertThat(thirdSync.statusCode()).isEqualTo(202);
    assertThat(
            fixture.inTenant(
                shop.tenant(),
                () ->
                    jdbc.queryForObject(
                        "SELECT count(*) FROM channel_listing WHERE channel_account_id = ?",
                        Long.class,
                        account)))
        .isEqualTo(rowCount);
  }

  @Test
  void terminalOrdersUnchangedByMappingPutOrSweeper() throws Exception {
    StockFixture.Shop shop = fixture.shop("ACTIVE");
    String shopId = fixture.tsfShopId(shop);
    UUID account = fixture.tsfChannelAccount(shop, "ACTIVE", "CONNECTED");
    UUID sku = fixture.sku(shop, 5);
    fixture.channelListing(shop, account, "L-term-r3", null, true, false);
    UUID listingId = listingIdFromApi(shop, shopId, account, "L-term-r3");

    String cancelledId = "TSF-R3-TERM-C-" + UUID.randomUUID();
    ingest(
        OrderIntakeScenarioSupport.orderCreated(
            JSON, cancelledId, shopId, null, "COD", "L-term-r3", 1, 1));
    worker.processAvailable(10);
    ingest(OrderIntakeScenarioSupport.orderCancelled(JSON, cancelledId, shopId, 2));
    worker.processAvailable(10);

    String completedId = "TSF-R3-TERM-D-" + UUID.randomUUID();
    ingest(
        OrderIntakeScenarioSupport.orderCreated(
            JSON, completedId, shopId, null, "COD", "L-term-r3", 1, 1));
    worker.processAvailable(10);
    UUID completedOrderId = orderId(shop, completedId);
    fixture.inTenant(
        shop.tenant(),
        () ->
            jdbc.update(
                """
                UPDATE sales_order
                SET order_status = 'COMPLETED', fulfillment_status = 'DELIVERED', hold_reason = 'NONE'
                WHERE id = ?
                """,
                completedOrderId));

    String pendingId = "TSF-R3-TERM-P-" + UUID.randomUUID();
    registerMockOrder(pendingId);
    ingest(
        OrderIntakeScenarioSupport.orderCreated(
            JSON, pendingId, shopId, null, "COD", "L-term-r3", 1, 1));
    worker.processAvailable(10);
    UUID pendingOrderId = orderId(shop, pendingId);
    HttpResponse<String> cancelReq =
        HTTP.send(
            HttpRequest.newBuilder(
                    URI.create(
                        "http://127.0.0.1:"
                            + port
                            + "/api/v1/orders/"
                            + pendingOrderId
                            + "/cancel-requests"))
                .timeout(HTTP_TIMEOUT)
                .header("Authorization", "Bearer " + userToken(shopId))
                .header("Content-Type", "application/json")
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        "{\"reason\":\"buyer asked\"}", StandardCharsets.UTF_8))
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    assertThat(cancelReq.statusCode()).isIn(202, 409);
    assertThat(holdReason(shop, pendingId)).isEqualTo("CHANNEL_CANCEL_PENDING");

    OrderSnapshot cancelled = snapshot(shop, cancelledId);
    OrderSnapshot completed = snapshot(shop, completedId);
    OrderSnapshot pending = snapshot(shop, pendingId);

    assertThat(httpPutMapping(userToken(shopId), listingId, sku).statusCode()).isEqualTo(200);
    resolverJob.runScheduledBatch();

    assertThat(snapshot(shop, cancelledId)).isEqualTo(cancelled);
    assertThat(snapshot(shop, completedId)).isEqualTo(completed);
    assertThat(snapshot(shop, pendingId)).isEqualTo(pending);
  }

  @Test
  void scenarioTenantIsolationListingGet404OtherTenant() throws Exception {
    StockFixture.Shop shopA = fixture.shop("ACTIVE");
    String shopAId = fixture.tsfShopId(shopA);
    UUID accountA = fixture.tsfChannelAccount(shopA, "ACTIVE", "CONNECTED");
    fixture.channelListing(shopA, accountA, "L-iso-a", null, true, false);
    UUID listingA = listingIdFromApi(shopA, shopAId, accountA, "L-iso-a");

    StockFixture.Shop shopB = fixture.shop("ACTIVE");
    String shopBId = fixture.tsfShopId(shopB);
    HttpResponse<String> response =
        HTTP.send(
            HttpRequest.newBuilder(
                    URI.create("http://127.0.0.1:" + port + "/api/v1/channel-listings/" + listingA))
                .timeout(HTTP_TIMEOUT)
                .header("Authorization", "Bearer " + userToken(shopBId))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    assertThat(response.statusCode()).isEqualTo(404);
  }

  @Test
  void scenarioTenantIsolationPutMapping404OtherTenant() throws Exception {
    StockFixture.Shop shopA = fixture.shop("ACTIVE");
    String shopAId = fixture.tsfShopId(shopA);
    UUID accountA = fixture.tsfChannelAccount(shopA, "ACTIVE", "CONNECTED");
    UUID skuA = fixture.sku(shopA, 3);
    fixture.channelListing(shopA, accountA, "L-iso-put", null, true, false);
    UUID listingA = listingIdFromApi(shopA, shopAId, accountA, "L-iso-put");

    StockFixture.Shop shopB = fixture.shop("ACTIVE");
    String shopBId = fixture.tsfShopId(shopB);
    UUID skuB = fixture.sku(shopB, 3);
    assertThat(httpPutMapping(userToken(shopBId), listingA, skuB).statusCode()).isEqualTo(404);
  }

  @Test
  void scenarioBundleComponentlessOutOfStockNoEngineKey() throws Exception {
    StockFixture.Shop shop = fixture.shop("ACTIVE");
    String shopId = fixture.tsfShopId(shop);
    UUID account = fixture.tsfChannelAccount(shop, "CONTROL", "CONNECTED");
    UUID bundle = fixture.componentlessBundle(shop);
    fixture.channelListing(shop, account, "L-r3-bnd", bundle, true);

    long keysBefore = ensureHoldKeyCount(shop);
    String externalOrderId = "TSF-R3-BND-" + UUID.randomUUID();
    ingest(
        OrderIntakeScenarioSupport.orderCreated(
            JSON, externalOrderId, shopId, UuidV7.generate().toString(), "COD", "L-r3-bnd", 1, 1));
    worker.processAvailable(10);
    assertThat(holdReason(shop, externalOrderId)).isEqualTo("OUT_OF_STOCK");
    assertThat(ensureHoldKeyCount(shop)).isEqualTo(keysBefore);
  }

  @Test
  void scenarioObserveModeNoneHold() throws Exception {
    StockFixture.Shop shop = fixture.shop("ACTIVE");
    String shopId = fixture.tsfShopId(shop);
    UUID account = fixture.tsfChannelAccount(shop, "OBSERVE", "CONNECTED");
    UUID bundle = fixture.componentlessBundle(shop);
    fixture.channelListing(shop, account, "L-r3-obs", bundle, true);

    String externalOrderId = "TSF-R3-OBS-" + UUID.randomUUID();
    ingest(
        OrderIntakeScenarioSupport.orderCreated(
            JSON, externalOrderId, shopId, UuidV7.generate().toString(), "COD", "L-r3-obs", 1, 1));
    worker.processAvailable(10);
    assertThat(holdReason(shop, externalOrderId)).isEqualTo("NONE");
  }

  @Test
  void scenarioPrepaidUnpaidStaysUnfulfilledAfterMap() throws Exception {
    StockFixture.Shop shop = fixture.shop("ACTIVE");
    String shopId = fixture.tsfShopId(shop);
    UUID account = fixture.tsfChannelAccount(shop, "ACTIVE", "CONNECTED");
    UUID sku = fixture.sku(shop, 10);
    fixture.channelListing(shop, account, "L-r3-pp", null, true, false);

    String externalOrderId = "TSF-R3-PP-" + UUID.randomUUID();
    ingest(
        OrderIntakeScenarioSupport.orderCreated(
            JSON, externalOrderId, shopId, null, "PREPAID", "L-r3-pp", 1, 1));
    worker.processAvailable(10);
    UUID listingId = listingIdFromApi(shop, shopId, account, "L-r3-pp");
    assertThat(httpPutMapping(userToken(shopId), listingId, sku).statusCode()).isEqualTo(200);
    assertThat(holdReason(shop, externalOrderId)).isEqualTo("NONE");
    assertThat(fulfillmentStatus(shop, externalOrderId)).isEqualTo("UNFULFILLED");
  }

  @Test
  void scenarioThreeHeldOrdersMapTwoReleasedOneHeld() throws Exception {
    StockFixture.Shop shop = fixture.shop("ACTIVE");
    String shopId = fixture.tsfShopId(shop);
    UUID account = fixture.tsfChannelAccount(shop, "ACTIVE", "CONNECTED");
    UUID sku = fixture.sku(shop, 2);
    fixture.channelListing(shop, account, "L-r3-triple", null, true, false);

    List<String> orderIds = new ArrayList<>();
    for (int i = 0; i < 3; i++) {
      String externalOrderId = "TSF-R3-TRI-" + i + "-" + UUID.randomUUID();
      orderIds.add(externalOrderId);
      ingest(
          OrderIntakeScenarioSupport.orderCreated(
              JSON, externalOrderId, shopId, null, "COD", "L-r3-triple", 1, 1));
      worker.processAvailable(10);
      assertThat(holdReason(shop, externalOrderId)).isEqualTo("SKU_NOT_MAPPED");
    }

    UUID listingId = listingIdFromApi(shop, shopId, account, "L-r3-triple");
    JsonNode reeval =
        JSON.readTree(httpPutMapping(userToken(shopId), listingId, sku).body())
            .path("reevaluation");
    assertThat(reeval.path("released").asInt()).isEqualTo(2);
    assertThat(reeval.path("out_of_stock").asInt()).isEqualTo(1);
    assertThat(reeval.path("still_held").asInt()).isZero();

    String heldExternal =
        orderIds.stream()
            .filter(id -> "OUT_OF_STOCK".equals(holdReason(shop, id)))
            .findFirst()
            .orElseThrow();
    UUID heldOrderId = orderId(shop, heldExternal);
    assertThat(fulfillmentReadyCount(shop, heldOrderId)).isZero();
    long heldHistory = historyCount(shop, heldOrderId);
    resolverJob.runScheduledBatch();
    assertThat(historyCount(shop, heldOrderId)).isEqualTo(heldHistory);
  }

  @Test
  void controlModeStubMapsAndReservesLikeIntake() throws Exception {
    StockFixture.Shop shop = fixture.shop("ACTIVE");
    String shopId = fixture.tsfShopId(shop);
    UUID account = fixture.tsfChannelAccount(shop, "CONTROL", "CONNECTED");
    UUID sku = fixture.sku(shop, 20);

    String listingSku = "L-r3-stub-" + UUID.randomUUID();
    String externalOrderId = "TSF-R3-CTL-" + UUID.randomUUID();
    ingest(
        OrderIntakeScenarioSupport.orderCreated(
            JSON, externalOrderId, shopId, null, "COD", listingSku, 1, 1));
    worker.processAvailable(10);

    Boolean stockControl =
        fixture.inTenant(
            shop.tenant(),
            () ->
                jdbc.queryForObject(
                    """
                    SELECT stock_control FROM channel_listing
                    WHERE channel_account_id = ? AND external_sku_id = ?
                    """,
                    Boolean.class,
                    account,
                    listingSku));
    assertThat(stockControl).isFalse();
    assertThat(holdReason(shop, externalOrderId)).isEqualTo("SKU_NOT_MAPPED");

    UUID listingId = listingIdFromApi(shop, shopId, account, listingSku);
    assertThat(httpPutMapping(userToken(shopId), listingId, sku).statusCode()).isEqualTo(200);

    Boolean stockControlAfter =
        fixture.inTenant(
            shop.tenant(),
            () ->
                jdbc.queryForObject(
                    """
                    SELECT stock_control FROM channel_listing
                    WHERE channel_account_id = ? AND external_sku_id = ?
                    """,
                    Boolean.class,
                    account,
                    listingSku));
    assertThat(stockControlAfter).isFalse();
    assertThat(activeOrderReservations(shop, externalOrderId)).isEqualTo(1);
    assertThat(holdReason(shop, externalOrderId)).isEqualTo("NONE");
    assertThat(fulfillmentStatus(shop, externalOrderId)).isEqualTo("READY_TO_PICK");
  }

  @Test
  void lateMapShadowZeroStockOutOfStockWithShadowDiff() throws Exception {
    StockFixture.Shop shop = fixture.shop("ACTIVE");
    String shopId = fixture.tsfShopId(shop);
    UUID account = fixture.tsfChannelAccount(shop, "SHADOW", "CONNECTED");
    UUID sku = fixture.sku(shop, 0);
    fixture.channelListing(shop, account, "L-r4-sh-oos", null, true, false);

    String externalOrderId = "TSF-R4-SH-OOS-" + UUID.randomUUID();
    ingest(
        OrderIntakeScenarioSupport.orderCreated(
            JSON, externalOrderId, shopId, null, "COD", "L-r4-sh-oos", 1, 1));
    worker.processAvailable(10);
    assertThat(holdReason(shop, externalOrderId)).isEqualTo("SKU_NOT_MAPPED");

    UUID listingId = listingIdFromApi(shop, shopId, account, "L-r4-sh-oos");
    assertThat(httpPutMapping(userToken(shopId), listingId, sku).statusCode()).isEqualTo(200);

    assertThat(holdReason(shop, externalOrderId)).isEqualTo("OUT_OF_STOCK");
    String note = holdNote(shop, externalOrderId);
    assertThat(note).isNotBlank();
    assertThat(
            fixture.inTenant(
                shop.tenant(),
                () ->
                    jdbc.queryForObject(
                        "SELECT count(*) FROM shadow_diff WHERE kind = 'ORDER' AND ref = ?",
                        Long.class,
                        externalOrderId)))
        .isEqualTo(1);
  }

  @Test
  void lateMapComponentlessBundleOutOfStockNoEngineKey() throws Exception {
    for (String mode : List.of("SHADOW", "CONTROL")) {
      StockFixture.Shop shop = fixture.shop("ACTIVE");
      String shopId = fixture.tsfShopId(shop);
      UUID account = fixture.tsfChannelAccount(shop, mode, "CONNECTED");
      UUID bundle = fixture.componentlessBundle(shop);
      String listingSku = "L-r4-bnd-" + mode + "-" + UUID.randomUUID();
      fixture.channelListing(shop, account, listingSku, null, true, false);

      String externalOrderId = "TSF-R4-BND-" + mode + "-" + UUID.randomUUID();
      ingest(
          OrderIntakeScenarioSupport.orderCreated(
              JSON, externalOrderId, shopId, null, "COD", listingSku, 1, 1));
      worker.processAvailable(10);
      assertThat(holdReason(shop, externalOrderId)).isEqualTo("SKU_NOT_MAPPED");

      long keysBefore = ensureHoldKeyCount(shop);
      UUID listingId = listingIdFromApi(shop, shopId, account, listingSku);
      assertThat(httpPutMapping(userToken(shopId), listingId, bundle).statusCode()).isEqualTo(200);

      assertThat(holdReason(shop, externalOrderId)).isEqualTo("OUT_OF_STOCK");
      assertThat(holdNote(shop, externalOrderId))
          .isEqualTo(
              com.thaishopfun.oms.order.hold.OrderHoldEffects.BUNDLE_WITHOUT_COMPONENTS_NOTE);
      assertThat(ensureHoldKeyCount(shop)).isEqualTo(keysBefore);
      if ("SHADOW".equals(mode)) {
        assertThat(
                fixture.inTenant(
                    shop.tenant(),
                    () ->
                        jdbc.queryForObject(
                            "SELECT count(*) FROM shadow_diff WHERE kind = 'ORDER' AND ref = ?",
                            Long.class,
                            externalOrderId)))
            .isEqualTo(1);
      }
    }
  }

  @Test
  void lateMapObserveNoneZeroReservations() throws Exception {
    StockFixture.Shop shop = fixture.shop("ACTIVE");
    String shopId = fixture.tsfShopId(shop);
    UUID account = fixture.tsfChannelAccount(shop, "OBSERVE", "CONNECTED");
    UUID sku = fixture.sku(shop, 5);
    fixture.channelListing(shop, account, "L-r4-obs", null, true, false);

    String externalOrderId = "TSF-R4-OBS-" + UUID.randomUUID();
    ingest(
        OrderIntakeScenarioSupport.orderCreated(
            JSON, externalOrderId, shopId, null, "COD", "L-r4-obs", 1, 1));
    worker.processAvailable(10);
    UUID listingId = listingIdFromApi(shop, shopId, account, "L-r4-obs");
    assertThat(httpPutMapping(userToken(shopId), listingId, sku).statusCode()).isEqualTo(200);

    assertThat(holdReason(shop, externalOrderId)).isEqualTo("NONE");
    assertThat(activeOrderReservations(shop, externalOrderId)).isZero();
  }

  private record OrderSnapshot(String orderStatus, String fulfillmentStatus, String holdReason) {}

  private OrderSnapshot snapshot(StockFixture.Shop shop, String externalOrderId) {
    return fixture.inTenant(
        shop.tenant(),
        () ->
            jdbc.queryForObject(
                """
                SELECT order_status, fulfillment_status, hold_reason
                FROM sales_order WHERE external_order_id = ?
                """,
                (rs, row) ->
                    new OrderSnapshot(
                        rs.getString("order_status"),
                        rs.getString("fulfillment_status"),
                        rs.getString("hold_reason")),
                externalOrderId));
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

  private long historyCount(StockFixture.Shop shop, UUID orderId) {
    return fixture.inTenant(
        shop.tenant(),
        () ->
            jdbc.queryForObject(
                "SELECT count(*) FROM order_status_history WHERE order_id = ?",
                Long.class,
                orderId));
  }

  private long fulfillmentReadyCount(StockFixture.Shop shop, UUID orderId) {
    return fixture.inTenant(
        shop.tenant(),
        () ->
            jdbc.queryForObject(
                """
                SELECT count(*) FROM order_status_history
                WHERE order_id = ? AND dimension = 'FULFILLMENT' AND to_value = 'READY_TO_PICK'
                """,
                Long.class,
                orderId));
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

  private long reservedLedgerRows(StockFixture.Shop shop, UUID orderId) {
    return fixture.inTenant(
        shop.tenant(),
        () ->
            jdbc.queryForObject(
                """
                SELECT count(*) FROM inventory_ledger il
                JOIN stock_reservation sr ON il.ref_id = sr.id
                WHERE sr.owner_type = 'ORDER' AND sr.owner_ref = ? AND il.delta_reserved > 0
                """,
                Long.class,
                orderId.toString()));
  }

  private long auditCount(StockFixture.Shop shop, String action, UUID entityId) {
    return fixture.inTenant(
        shop.tenant(),
        () ->
            jdbc.queryForObject(
                """
                SELECT count(*) FROM audit_log
                WHERE action = ? AND entity_id = ?
                """,
                Long.class,
                action,
                entityId.toString()));
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

  private String holdNote(StockFixture.Shop shop, String externalOrderId) {
    return fixture.inTenant(
        shop.tenant(),
        () ->
            jdbc.queryForObject(
                "SELECT hold_note FROM sales_order WHERE external_order_id = ?",
                String.class,
                externalOrderId));
  }

  private String orderStatus(StockFixture.Shop shop, String externalOrderId) {
    return fixture.inTenant(
        shop.tenant(),
        () ->
            jdbc.queryForObject(
                "SELECT order_status FROM sales_order WHERE external_order_id = ?",
                String.class,
                externalOrderId));
  }

  private String fulfillmentStatus(StockFixture.Shop shop, String externalOrderId) {
    return fixture.inTenant(
        shop.tenant(),
        () ->
            jdbc.queryForObject(
                "SELECT fulfillment_status FROM sales_order WHERE external_order_id = ?",
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

  private String mappingSource(StockFixture.Shop shop, UUID account, String externalSkuId) {
    return fixture.inTenant(
        shop.tenant(),
        () ->
            jdbc.queryForObject(
                """
                SELECT mapping_source FROM channel_listing
                WHERE channel_account_id = ? AND external_sku_id = ?
                """,
                String.class,
                account,
                externalSkuId));
  }

  private UUID skuIdOnListing(StockFixture.Shop shop, UUID account, String externalSkuId) {
    return fixture.inTenant(
        shop.tenant(),
        () ->
            jdbc.queryForObject(
                """
                SELECT sku_id FROM channel_listing
                WHERE channel_account_id = ? AND external_sku_id = ?
                """,
                UUID.class,
                account,
                externalSkuId));
  }

  private String sellerSku(StockFixture.Shop shop, UUID account, String externalSkuId) {
    return fixture.inTenant(
        shop.tenant(),
        () ->
            jdbc.queryForObject(
                """
                SELECT seller_sku FROM channel_listing
                WHERE channel_account_id = ? AND external_sku_id = ?
                """,
                String.class,
                account,
                externalSkuId));
  }

  private String listingName(StockFixture.Shop shop, UUID account, String externalSkuId) {
    return fixture.inTenant(
        shop.tenant(),
        () ->
            jdbc.queryForObject(
                """
                SELECT name FROM channel_listing
                WHERE channel_account_id = ? AND external_sku_id = ?
                """,
                String.class,
                account,
                externalSkuId));
  }

  private Instant removedAt(StockFixture.Shop shop, UUID account, String externalSkuId) {
    return fixture.inTenant(
        shop.tenant(),
        () -> {
          List<java.time.OffsetDateTime> values =
              jdbc.query(
                  """
                  SELECT removed_at FROM channel_listing
                  WHERE channel_account_id = ? AND external_sku_id = ?
                  """,
                  (rs, row) -> rs.getObject("removed_at", java.time.OffsetDateTime.class),
                  account,
                  externalSkuId);
          if (values.isEmpty()) {
            return null;
          }
          java.time.OffsetDateTime removed = values.get(0);
          return removed == null ? null : removed.toInstant();
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
