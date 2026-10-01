package com.thaishopfun.oms.checkout;

import static org.assertj.core.api.Assertions.assertThat;

import com.thaishopfun.mocktsf.contract.ContractValidator;
import com.thaishopfun.oms.auth.AuthTestSupport;
import com.thaishopfun.oms.stock.ReservationEngine;
import com.thaishopfun.oms.stock.StockExpiryJob;
import com.thaishopfun.oms.stock.StockFixture;
import com.thaishopfun.oms.stock.StockTestConfig;
import com.thaishopfun.oms.stock.StockTestConfig.Fault;
import com.thaishopfun.oms.stock.StockTestConfig.FaultHooks;
import com.thaishopfun.oms.tenant.TenantContext;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * T12A acceptance from the brief: modes, idempotency, concurrency, contracts, DELETE, isolation.
 * Engine-heavy cases (inline expiry sweeper) stay in {@link com.thaishopfun.oms.stock}.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(StockTestConfig.class)
class CheckoutT12AAcceptanceTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final HttpClient HTTP =
      HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(10)).build();
  private static final ContractValidator CONTRACT = ContractValidator.classpath();

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    AuthTestSupport.register(registry);
  }

  @LocalServerPort private int port;
  @Autowired JdbcTemplate jdbc;
  @Autowired PlatformTransactionManager transactions;
  @Autowired ReservationEngine engine;
  @Autowired FaultHooks faults;
  @Autowired StockExpiryJob expiryJob;

  StockFixture fixture;

  @BeforeEach
  void setup() {
    fixture = new StockFixture(jdbc, transactions);
    faults.reset();
  }

  @AfterEach
  void clear() {
    faults.reset();
    TenantContext.clear();
  }

  @Test
  void allOrNothingBundleShortReturns409WithNothingHeld() throws Exception {
    StockFixture.Shop shop = fixture.shop("ACTIVE");
    String shopId = fixture.tsfShopId(shop);
    UUID account = fixture.channelAccount(shop, "ACTIVE", "CONNECTED");
    UUID a = fixture.sku(shop, 10);
    UUID b = fixture.sku(shop, 0);
    UUID bundle = fixture.bundle(shop, Map.of(a, 1, b, 1));
    fixture.channelListing(shop, account, "L-bundle", bundle, true);
    HttpResponse<String> response =
        post("chk-bundle", request(shopId, "chk-bundle", "L-bundle", 1));
    assertThat(response.statusCode()).isEqualTo(409);
    assertThat(CONTRACT.restErrors("reservation-conflict", response.body())).isEmpty();
    assertThat(fixture.reserved(shop, a)).isZero();
    assertThat(fixture.reserved(shop, b)).isZero();
    fixture.assertInvariants(shop);
  }

  @Test
  void controlModeEnforcesWhenStockControl() throws Exception {
    StockFixture.Shop shop = fixture.shop("ACTIVE");
    String shopId = fixture.tsfShopId(shop);
    UUID account = fixture.channelAccount(shop, "CONTROL", "CONNECTED");
    UUID sku = fixture.sku(shop, 3);
    fixture.channelListing(shop, account, "L-ctrl", sku, true);
    HttpResponse<String> ok = post("chk-ctrl", request(shopId, "chk-ctrl", "L-ctrl", 2));
    assertThat(ok.statusCode()).isEqualTo(201);
    assertThat(JSON.readTree(ok.body()).path("enforced").asBoolean()).isTrue();
    assertThat(fixture.reserved(shop, sku)).isEqualTo(2);
  }

  @Test
  void observeDisconnectedUnmappedAndNoWarehouseAreUnenforced() throws Exception {
    StockFixture.Shop observe = fixture.shop("ACTIVE");
    UUID obsAcct = fixture.channelAccount(observe, "OBSERVE", "CONNECTED");
    UUID sku = fixture.sku(observe, 5);
    fixture.channelListing(observe, obsAcct, "L-obs", sku, true);
    HttpResponse<String> obs =
        post("chk-obs", request(fixture.tsfShopId(observe), "chk-obs", "L-obs", 1));
    assertThat(obs.statusCode()).isEqualTo(201);
    assertThat(JSON.readTree(obs.body()).path("enforced").asBoolean()).isFalse();
    assertThat(fixture.reserved(observe, sku)).isZero();

    StockFixture.Shop disc = fixture.shop("ACTIVE");
    UUID discAcct = fixture.channelAccount(disc, "ACTIVE", "DISCONNECTED");
    fixture.channelListing(disc, discAcct, "L-disc", fixture.sku(disc, 5), true);
    HttpResponse<String> disconnected =
        post("chk-disc", request(fixture.tsfShopId(disc), "chk-disc", "L-disc", 1));
    assertThat(JSON.readTree(disconnected.body()).path("enforced").asBoolean()).isFalse();

    StockFixture.Shop noAcct = fixture.shop("ACTIVE");
    HttpResponse<String> noAccount =
        post("chk-no-acct", request(fixture.tsfShopId(noAcct), "chk-no-acct", "L-x", 1));
    assertThat(JSON.readTree(noAccount.body()).path("enforced").asBoolean()).isFalse();

    StockFixture.Shop unmapped = fixture.shop("ACTIVE");
    UUID activeAcct = fixture.channelAccount(unmapped, "ACTIVE", "CONNECTED");
    fixture.channelListing(unmapped, activeAcct, "L-map", fixture.sku(unmapped, 1), true);
    HttpResponse<String> missing =
        post("chk-unmapped", request(fixture.tsfShopId(unmapped), "chk-unmapped", "L-missing", 1));
    assertThat(JSON.readTree(missing.body()).path("enforced").asBoolean()).isFalse();

    StockFixture.Shop noWh = fixture.shop("ACTIVE", false);
    UUID whAcct = fixture.channelAccount(noWh, "ACTIVE", "CONNECTED");
    fixture.channelListing(noWh, whAcct, "L-nowh", fixture.sku(noWh, 1), true);
    HttpResponse<String> noWarehouse =
        post("chk-nowh", request(fixture.tsfShopId(noWh), "chk-nowh", "L-nowh", 1));
    assertThat(JSON.readTree(noWarehouse.body()).path("enforced").asBoolean()).isFalse();
  }

  @Test
  void suspendedExpiredGraceAndStockSyncPaused() throws Exception {
    StockFixture.Shop suspended = fixture.shop("SUSPENDED");
    UUID suspAcct = fixture.channelAccount(suspended, "ACTIVE", "CONNECTED");
    UUID suspSku = fixture.sku(suspended, 5);
    fixture.channelListing(suspended, suspAcct, "L-susp", suspSku, true);
    HttpResponse<String> susp =
        post("chk-susp", request(fixture.tsfShopId(suspended), "chk-susp", "L-susp", 1));
    assertThat(JSON.readTree(susp.body()).path("enforced").asBoolean()).isFalse();

    StockFixture.Shop expired = fixture.shop("ACTIVE");
    fixture.setEntitlementExpiresAt(expired, Instant.now().minus(1, ChronoUnit.DAYS));
    UUID expAcct = fixture.channelAccount(expired, "ACTIVE", "CONNECTED");
    fixture.channelListing(expired, expAcct, "L-exp", fixture.sku(expired, 5), true);
    HttpResponse<String> entExpired =
        post("chk-exp", request(fixture.tsfShopId(expired), "chk-exp", "L-exp", 1));
    assertThat(JSON.readTree(entExpired.body()).path("enforced").asBoolean()).isFalse();

    StockFixture.Shop grace = fixture.shop("GRACE");
    UUID graceAcct = fixture.channelAccount(grace, "ACTIVE", "CONNECTED");
    UUID graceSku = fixture.sku(grace, 4);
    fixture.channelListing(grace, graceAcct, "L-grace", graceSku, true);
    HttpResponse<String> graceResp =
        post("chk-grace", request(fixture.tsfShopId(grace), "chk-grace", "L-grace", 2));
    assertThat(graceResp.statusCode()).isEqualTo(201);
    assertThat(JSON.readTree(graceResp.body()).path("enforced").asBoolean()).isFalse();
    assertThat(fixture.reserved(grace, graceSku)).isZero();

    StockFixture.Shop paused = fixture.shop("ACTIVE");
    UUID pausedAcct = fixture.channelAccount(paused, "ACTIVE", "CONNECTED");
    fixture.setStockSyncPaused(paused, pausedAcct, true);
    UUID pausedSku = fixture.sku(paused, 5);
    fixture.channelListing(paused, pausedAcct, "L-pause", pausedSku, true);
    HttpResponse<String> pausedResp =
        post("chk-pause", request(fixture.tsfShopId(paused), "chk-pause", "L-pause", 1));
    assertThat(JSON.readTree(pausedResp.body()).path("enforced").asBoolean()).isTrue();
    assertThat(fixture.reserved(paused, pausedSku)).isEqualTo(1);
  }

  @Test
  void unknownShopIsUnenforcedAndNotIdempotent() throws Exception {
    ObjectNode body = request("shop-unknown-" + UUID.randomUUID(), "chk-unknown", "L-1", 1);
    HttpResponse<String> first = post("chk-unknown", body);
    HttpResponse<String> second = post("chk-unknown", body);
    assertThat(first.statusCode()).isEqualTo(201);
    assertThat(second.statusCode()).isEqualTo(201);
    String id1 = JSON.readTree(first.body()).path("reservation_id").asString();
    String id2 = JSON.readTree(second.body()).path("reservation_id").asString();
    assertThat(id1).isNotEqualTo(id2);
    assertThat(JSON.readTree(first.body()).path("enforced").asBoolean()).isFalse();
  }

  @Test
  @Timeout(value = 2, unit = TimeUnit.MINUTES)
  void twentyConcurrentIdenticalRequestsHaveOneEffect() throws Exception {
    StockFixture.Shop shop = fixture.shop("ACTIVE");
    String shopId = fixture.tsfShopId(shop);
    UUID account = fixture.channelAccount(shop, "ACTIVE", "CONNECTED");
    UUID sku = fixture.sku(shop, 100);
    fixture.channelListing(shop, account, "L-conc", sku, true);
    ObjectNode body = request(shopId, "chk-20", "L-conc", 3);
    int threads = 20;
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    try {
      List<Future<HttpResponse<String>>> futures = new ArrayList<>();
      for (int i = 0; i < threads; i++) {
        futures.add(pool.submit(() -> post("chk-20", body)));
      }
      Set<JsonNode> bodies = new HashSet<>();
      for (Future<HttpResponse<String>> future : futures) {
        HttpResponse<String> response = future.get(90, TimeUnit.SECONDS);
        assertThat(response.statusCode()).isEqualTo(201);
        bodies.add(JSON.readTree(response.body()));
      }
      assertThat(bodies).hasSize(1);
    } finally {
      pool.shutdownNow();
    }
    assertThat(fixture.reserved(shop, sku)).isEqualTo(3);
    fixture.assertInvariants(shop);
  }

  @Test
  void outOfStockReplayAfterRestockReturnsSame409() throws Exception {
    StockFixture.Shop shop = fixture.shop("ACTIVE");
    String shopId = fixture.tsfShopId(shop);
    UUID account = fixture.channelAccount(shop, "ACTIVE", "CONNECTED");
    UUID sku = fixture.sku(shop, 1);
    fixture.channelListing(shop, account, "L-oos", sku, true);
    HttpResponse<String> first = post("chk-oos", request(shopId, "chk-oos", "L-oos", 5));
    assertThat(first.statusCode()).isEqualTo(409);
    fixture.receive(shop, sku, 100);
    HttpResponse<String> replay = post("chk-oos", request(shopId, "chk-oos", "L-oos", 5));
    assertThat(replay.statusCode()).isEqualTo(409);
    assertThat(JSON.readTree(replay.body())).isEqualTo(JSON.readTree(first.body()));
    assertThat(fixture.reserved(shop, sku)).isZero();
  }

  @Test
  void shadowReplayWritesNoSecondShadowDiff() throws Exception {
    StockFixture.Shop shop = fixture.shop("ACTIVE");
    String shopId = fixture.tsfShopId(shop);
    UUID account = fixture.channelAccount(shop, "SHADOW", "CONNECTED");
    UUID sku = fixture.sku(shop, 5);
    fixture.channelListing(shop, account, "L-sh-a", sku, true);
    fixture.channelListing(shop, account, "L-sh-b", sku, true);
    ObjectNode body = request(shopId, "chk-sh-replay", List.of("L-sh-a", "L-sh-b"), List.of(4, 4));
    HttpResponse<String> first = post("chk-sh-replay", body);
    assertThat(first.statusCode()).isEqualTo(201);
    JsonNode omsValue =
        fixture.inTenant(
            shop.tenant(),
            () ->
                JSON.readTree(
                    jdbc.queryForObject(
                        "SELECT oms_value::text FROM shadow_diff WHERE ref = ?",
                        String.class,
                        "chk-sh-replay")));
    assertThat(omsValue.path("would_reserve").asBoolean()).isFalse();
    assertThat(omsValue.path("items")).hasSize(2);
    post("chk-sh-replay", body);
    long diffs =
        fixture.inTenant(
            shop.tenant(),
            () ->
                jdbc.queryForObject(
                    "SELECT count(*) FROM shadow_diff WHERE ref = ?", Long.class, "chk-sh-replay"));
    assertThat(diffs).isEqualTo(1);
  }

  @Test
  void deleteWritesReleaseAndRepeatDeleteIsIdempotent() throws Exception {
    StockFixture.Shop shop = fixture.shop("ACTIVE");
    String shopId = fixture.tsfShopId(shop);
    UUID account = fixture.channelAccount(shop, "ACTIVE", "CONNECTED");
    UUID sku = fixture.sku(shop, 8);
    fixture.channelListing(shop, account, "L-del", sku, true);
    HttpResponse<String> created = post("chk-del", request(shopId, "chk-del", "L-del", 3));
    String reservationId = JSON.readTree(created.body()).path("reservation_id").asString();
    assertThat(delete(reservationId).statusCode()).isEqualTo(204);
    assertThat(fixture.ledger(shop, "RELEASE")).isEqualTo(1);
    assertThat(fixture.reserved(shop, sku)).isZero();
    assertThat(delete(reservationId).statusCode()).isEqualTo(204);
    assertThat(fixture.ledger(shop, "RELEASE")).isEqualTo(1);
    fixture.assertInvariants(shop);
  }

  @Test
  void orderHeldGroupStaysHeldOnDelete() throws Exception {
    StockFixture.Shop shop = fixture.shop("ACTIVE");
    UUID sku = fixture.sku(shop, 5);
    String checkoutRef = "chk-order-held";
    UUID group =
        fixture.inTenant(
            shop.tenant(),
            () ->
                engine
                    .reserve(
                        com.thaishopfun.oms.stock.StockOwner.order("ord-held"),
                        List.of(com.thaishopfun.oms.stock.ReserveItem.of(sku, 2)),
                        "engine-key")
                    .reservationGroupId());
    assertThat(delete(group.toString()).statusCode()).isEqualTo(204);
    assertThat(fixture.reserved(shop, sku)).isEqualTo(2);
    assertThat(fixture.reservations(shop, "ACTIVE")).isEqualTo(1);
    fixture.assertInvariants(shop);
  }

  @Test
  void tenantIsolationOnDelete() throws Exception {
    StockFixture.Shop a = fixture.shop("ACTIVE");
    StockFixture.Shop b = fixture.shop("ACTIVE");
    UUID skuB = fixture.sku(b, 3);
    UUID groupB =
        fixture.inTenant(
            b.tenant(),
            () ->
                engine
                    .reserve(
                        com.thaishopfun.oms.stock.StockOwner.checkout("chk-b"),
                        List.of(com.thaishopfun.oms.stock.ReserveItem.of(skuB, 1)),
                        "k-b")
                    .reservationGroupId());
    UUID skuA = fixture.sku(a, 3);
    UUID groupA =
        fixture.inTenant(
            a.tenant(),
            () ->
                engine
                    .reserve(
                        com.thaishopfun.oms.stock.StockOwner.checkout("chk-a"),
                        List.of(com.thaishopfun.oms.stock.ReserveItem.of(skuA, 1)),
                        "k-a")
                    .reservationGroupId());
    assertThat(delete(groupA.toString()).statusCode()).isEqualTo(204);
    assertThat(fixture.reserved(a, skuA)).isZero();
    assertThat(fixture.reserved(b, skuB)).isEqualTo(1);
    assertThat(delete(groupB.toString()).statusCode()).isEqualTo(204);
    assertThat(fixture.reserved(b, skuB)).isZero();
    fixture.assertInvariants(a);
    fixture.assertInvariants(b);
  }

  @Test
  void checkoutReserveRetriesAfterInjectedDeadlock() throws Exception {
    StockFixture.Shop shop = fixture.shop("ACTIVE");
    String shopId = fixture.tsfShopId(shop);
    UUID account = fixture.channelAccount(shop, "ACTIVE", "CONNECTED");
    UUID sku = fixture.sku(shop, 10);
    fixture.channelListing(shop, account, "L-retry", sku, true);
    int firedBefore = faults.fired();
    faults.failNext(Fault.DEADLOCK);
    HttpResponse<String> response = post("chk-retry", request(shopId, "chk-retry", "L-retry", 2));
    assertThat(response.statusCode()).isEqualTo(201);
    assertThat(faults.fired()).isEqualTo(firedBefore + 1);
    assertThat(fixture.reserved(shop, sku)).isEqualTo(2);
    fixture.assertInvariants(shop);
  }

  @Test
  @Timeout(value = 3, unit = TimeUnit.MINUTES)
  void fiftyConcurrentCheckoutsOnTenUnits() throws Exception {
    // Bounded by Hikari maximum-pool-size (10) in application-test.yml; threads may queue.
    StockFixture.Shop shop = fixture.shop("ACTIVE");
    String shopId = fixture.tsfShopId(shop);
    UUID account = fixture.channelAccount(shop, "ACTIVE", "CONNECTED");
    UUID sku = fixture.sku(shop, 10);
    fixture.channelListing(shop, account, "L-race", sku, true);
    int threads = 50;
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    try {
      List<Callable<Integer>> tasks = new ArrayList<>();
      for (int i = 0; i < threads; i++) {
        String chk = "chk-race-" + i;
        tasks.add(() -> post(chk, request(shopId, chk, "L-race", 1)).statusCode());
      }
      List<Future<Integer>> futures = pool.invokeAll(tasks, 120, TimeUnit.SECONDS);
      int ok = 0;
      int conflict = 0;
      for (Future<Integer> future : futures) {
        int code = future.get(5, TimeUnit.SECONDS);
        if (code == 201) {
          ok++;
        } else if (code == 409) {
          conflict++;
        }
      }
      assertThat(ok).isEqualTo(10);
      assertThat(conflict).isEqualTo(40);
    } finally {
      pool.shutdownNow();
    }
    assertThat(fixture.reserved(shop, sku)).isEqualTo(10);
    fixture.assertInvariants(shop);
  }

  @Test
  void authMatrixReturns401() throws Exception {
    StockFixture.Shop shop = fixture.shop("ACTIVE");
    ObjectNode body = request(fixture.tsfShopId(shop), "chk-auth", "L-1", 1);
    assertThat(post("chk-auth", body, null).statusCode()).isEqualTo(401);
    String user =
        AuthTestSupport.userToken(
            "user-1", fixture.tsfShopId(shop), "ACTIVE", Instant.now().plusSeconds(3600), 1);
    assertThat(post("chk-auth", body, user).statusCode()).isEqualTo(401);
    String wrongAud =
        AuthTestSupport.token(
            "tsf-checkout",
            "shop",
            "ACTIVE",
            null,
            1,
            "oms",
            Instant.now().plusSeconds(600),
            List.of(),
            "SERVICE");
    assertThat(post("chk-auth", body, wrongAud).statusCode()).isEqualTo(401);
    String unknownClient =
        AuthTestSupport.token(
            "not-registered",
            "shop",
            "ACTIVE",
            null,
            1,
            "oms-internal",
            Instant.now().plusSeconds(600),
            List.of(),
            "SERVICE");
    assertThat(post("chk-auth", body, unknownClient).statusCode()).isEqualTo(401);
  }

  @Test
  void badRequestAndOutOfStockMatchContractSchemas() throws Exception {
    StockFixture.Shop shop = fixture.shop("ACTIVE");
    String shopId = fixture.tsfShopId(shop);
    UUID account = fixture.channelAccount(shop, "ACTIVE", "CONNECTED");
    UUID sku = fixture.sku(shop, 0);
    fixture.channelListing(shop, account, "L-contract", sku, true);
    HttpResponse<String> badKey =
        post("wrong-key", request(shopId, "chk-contract", "L-contract", 1));
    assertThat(badKey.statusCode()).isEqualTo(400);
    assertThat(CONTRACT.restErrors("error-bad-request", badKey.body())).isEmpty();
    HttpResponse<String> oos =
        post("chk-contract", request(shopId, "chk-contract", "L-contract", 1));
    assertThat(oos.statusCode()).isEqualTo(409);
    assertThat(CONTRACT.restErrors("reservation-conflict", oos.body())).isEmpty();
    assertThat(JSON.readTree(oos.body()).path("error").asString()).isEqualTo("OUT_OF_STOCK");
  }

  @Test
  void multipleListingsOnShortSkuReportEveryListing() throws Exception {
    StockFixture.Shop shop = fixture.shop("ACTIVE");
    String shopId = fixture.tsfShopId(shop);
    UUID account = fixture.channelAccount(shop, "ACTIVE", "CONNECTED");
    UUID shared = fixture.sku(shop, 0);
    fixture.channelListing(shop, account, "L-one", shared, true);
    fixture.channelListing(shop, account, "L-two", shared, true);
    ObjectNode body = request(shopId, "chk-multi", List.of("L-one", "L-two"), List.of(1, 1));
    HttpResponse<String> response = post("chk-multi", body);
    assertThat(response.statusCode()).isEqualTo(409);
    JsonNode items = JSON.readTree(response.body()).path("items");
    assertThat(items).hasSize(2);
  }

  @Test
  void idempotencyHashCollisionReturns409() throws Exception {
    StockFixture.Shop shop = fixture.shop("ACTIVE");
    String shopId = fixture.tsfShopId(shop);
    UUID account = fixture.channelAccount(shop, "ACTIVE", "CONNECTED");
    UUID sku = fixture.sku(shop, 10);
    fixture.channelListing(shop, account, "a:1|b", sku, true);
    fixture.channelListing(shop, account, "a", sku, true);
    fixture.channelListing(shop, account, "b", sku, true);
    post("chk-hash", request(shopId, "chk-hash", "a:1|b", 2));
    HttpResponse<String> conflict =
        post("chk-hash", request(shopId, "chk-hash", List.of("a", "b"), List.of(1, 2)));
    assertThat(conflict.statusCode()).isEqualTo(409);
    assertThat(JSON.readTree(conflict.body()).path("error").asString())
        .isEqualTo("IDEMPOTENCY_CONFLICT");
  }

  @Test
  void checkoutIdLongerThan255Is400() throws Exception {
    StockFixture.Shop shop = fixture.shop("ACTIVE");
    String longId = "x".repeat(256);
    ObjectNode body = request(fixture.tsfShopId(shop), longId, "L-1", 1);
    HttpResponse<String> response = post(longId, body);
    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(CONTRACT.restErrors("error-bad-request", response.body())).isEmpty();
  }

  @Test
  void multiItemCartBundleShortReportsListingAvailable() throws Exception {
    StockFixture.Shop shop = fixture.shop("ACTIVE");
    String shopId = fixture.tsfShopId(shop);
    UUID account = fixture.channelAccount(shop, "ACTIVE", "CONNECTED");
    UUID okSku = fixture.sku(shop, 10);
    UUID shortSku = fixture.sku(shop, 0);
    UUID bundle = fixture.bundle(shop, Map.of(okSku, 1, shortSku, 1));
    fixture.channelListing(shop, account, "L-ok", okSku, true);
    fixture.channelListing(shop, account, "L-bundle", bundle, true);
    HttpResponse<String> response =
        post("chk-cart", request(shopId, "chk-cart", List.of("L-ok", "L-bundle"), List.of(1, 1)));
    assertThat(response.statusCode()).isEqualTo(409);
    JsonNode items = JSON.readTree(response.body()).path("items");
    List<String> listings = new ArrayList<>();
    items.forEach(node -> listings.add(node.path("listing_sku_id").asString()));
    assertThat(listings).contains("L-bundle");
    JsonNode bundleRow = null;
    for (JsonNode row : items) {
      if ("L-bundle".equals(row.path("listing_sku_id").asString())) {
        bundleRow = row;
      }
    }
    assertThat(bundleRow).isNotNull();
    assertThat(bundleRow.path("available").asInt()).isZero();
    assertThat(fixture.reserved(shop, okSku)).isZero();
  }

  @Test
  void controlModeUnenforcedWhenStockControlFalse() throws Exception {
    StockFixture.Shop shop = fixture.shop("ACTIVE");
    String shopId = fixture.tsfShopId(shop);
    UUID account = fixture.channelAccount(shop, "CONTROL", "CONNECTED");
    UUID sku = fixture.sku(shop, 5);
    fixture.channelListing(shop, account, "L-no-ctrl", sku, false);
    HttpResponse<String> response =
        post("chk-no-ctrl", request(shopId, "chk-no-ctrl", "L-no-ctrl", 2));
    assertThat(response.statusCode()).isEqualTo(201);
    assertThat(JSON.readTree(response.body()).path("enforced").asBoolean()).isFalse();
    assertThat(fixture.reserved(shop, sku)).isZero();
  }

  @Test
  void nullSkuMappingIsUnenforced() throws Exception {
    StockFixture.Shop shop = fixture.shop("ACTIVE");
    String shopId = fixture.tsfShopId(shop);
    UUID account = fixture.channelAccount(shop, "ACTIVE", "CONNECTED");
    UUID sku = fixture.sku(shop, 5);
    fixture.channelListing(shop, account, "L-null", sku, true, false);
    HttpResponse<String> response = post("chk-null", request(shopId, "chk-null", "L-null", 1));
    assertThat(response.statusCode()).isEqualTo(201);
    assertThat(JSON.readTree(response.body()).path("enforced").asBoolean()).isFalse();
    assertThat(fixture.reserved(shop, sku)).isZero();
  }

  @Test
  void deleteLockTimeoutReturns503AndHoldStaysActive() throws Exception {
    StockFixture.Shop shop = fixture.shop("ACTIVE");
    String shopId = fixture.tsfShopId(shop);
    UUID account = fixture.channelAccount(shop, "ACTIVE", "CONNECTED");
    UUID sku = fixture.sku(shop, 3);
    fixture.channelListing(shop, account, "L-lock", sku, true);
    HttpResponse<String> created = post("chk-lock", request(shopId, "chk-lock", "L-lock", 2));
    String reservationId = JSON.readTree(created.body()).path("reservation_id").asString();
    faults.failNext(Fault.LOCK_TIMEOUT);
    HttpResponse<String> busy = delete(reservationId);
    assertThat(busy.statusCode()).isEqualTo(503);
    assertThat(busy.headers().firstValue("Retry-After")).contains("1");
    assertThat(fixture.reserved(shop, sku)).isEqualTo(2);
    fixture.assertInvariants(shop);
  }

  @Test
  @Timeout(value = 2, unit = TimeUnit.MINUTES)
  void concurrentOppositeSkuOrderWithSweeperDoesNotDeadlock() throws Exception {
    StockFixture.Shop shop = fixture.shop("ACTIVE");
    UUID a = fixture.sku(shop, 30);
    UUID b = fixture.sku(shop, 30);
    for (int i = 0; i < 4; i++) {
      int index = i;
      fixture.inTenant(
          shop.tenant(),
          () -> {
            UUID group =
                engine
                    .reserve(
                        com.thaishopfun.oms.stock.StockOwner.checkout("exp-" + index),
                        List.of(com.thaishopfun.oms.stock.ReserveItem.of(a, 1)),
                        "exp-" + index)
                    .reservationGroupId();
            jdbc.update(
                "UPDATE stock_reservation SET expires_at = now() - interval '1 minute' "
                    + "WHERE reservation_group_id = ?",
                group);
            return null;
          });
    }
    ExecutorService pool = Executors.newFixedThreadPool(8);
    try {
      CountDownLatch start = new CountDownLatch(1);
      List<Future<Void>> reserves = new ArrayList<>();
      for (int i = 0; i < 16; i++) {
        boolean reverse = i % 2 == 1;
        reserves.add(
            pool.submit(
                () -> {
                  start.await(30, TimeUnit.SECONDS);
                  TenantContext.set(shop.tenant(), null);
                  try {
                    engine.reserve(
                        com.thaishopfun.oms.stock.StockOwner.checkout("chk-" + UUID.randomUUID()),
                        reverse
                            ? List.of(
                                com.thaishopfun.oms.stock.ReserveItem.of(b, 1),
                                com.thaishopfun.oms.stock.ReserveItem.of(a, 1))
                            : List.of(
                                com.thaishopfun.oms.stock.ReserveItem.of(a, 1),
                                com.thaishopfun.oms.stock.ReserveItem.of(b, 1)),
                        "k-" + UUID.randomUUID());
                  } finally {
                    TenantContext.clear();
                  }
                  return null;
                }));
      }
      Future<?> sweeper =
          pool.submit(
              () -> {
                start.await(30, TimeUnit.SECONDS);
                for (int pass = 0; pass < 30; pass++) {
                  expiryJob.runOnce();
                  Thread.sleep(15);
                }
                return null;
              });
      start.countDown();
      for (Future<Void> future : reserves) {
        future.get(90, TimeUnit.SECONDS);
      }
      sweeper.get(90, TimeUnit.SECONDS);
      assertThat(fixture.reserved(shop, a)).isEqualTo(16);
      assertThat(fixture.reserved(shop, b)).isEqualTo(16);
      fixture.assertInvariants(shop);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  @Timeout(value = 1, unit = TimeUnit.MINUTES)
  void reserveReturns503WhenInventoryRowIsLocked() throws Exception {
    StockFixture.Shop shop = fixture.shop("ACTIVE");
    String shopId = fixture.tsfShopId(shop);
    UUID account = fixture.channelAccount(shop, "ACTIVE", "CONNECTED");
    UUID sku = fixture.sku(shop, 10);
    fixture.channelListing(shop, account, "L-block", sku, true);
    CountDownLatch locked = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    AtomicReference<Throwable> holderFailure = new AtomicReference<>();
    TransactionTemplate holdTx = new TransactionTemplate(transactions);
    holdTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    Thread holder =
        new Thread(
            () -> {
              TenantContext.set(shop.tenant(), null);
              try {
                holdTx.executeWithoutResult(
                    status -> {
                      jdbc.query(
                          "SELECT 1 FROM inventory WHERE sku_id = ? AND warehouse_id = ? FOR UPDATE",
                          (org.springframework.jdbc.core.RowCallbackHandler)
                              rs -> {
                                locked.countDown();
                                try {
                                  release.await(30, TimeUnit.SECONDS);
                                } catch (InterruptedException ex) {
                                  Thread.currentThread().interrupt();
                                  throw new IllegalStateException(ex);
                                }
                              },
                          sku,
                          shop.warehouse());
                    });
              } catch (Throwable ex) {
                holderFailure.set(ex);
              } finally {
                TenantContext.clear();
              }
            });
    holder.start();
    assertThat(locked.await(30, TimeUnit.SECONDS)).isTrue();
    long started = System.nanoTime();
    HttpResponse<String> busy = post("chk-block", request(shopId, "chk-block", "L-block", 1));
    release.countDown();
    holder.join(30_000);
    assertThat(holderFailure.get()).isNull();
    assertThat(busy.statusCode()).isEqualTo(503);
    assertThat(JSON.readTree(busy.body()).path("error").asString()).isEqualTo("STOCK_BUSY");
    assertThat(busy.headers().firstValue("Retry-After")).contains("1");
    assertThat(System.nanoTime() - started).isLessThan(TimeUnit.SECONDS.toNanos(8));
    assertThat(fixture.reserved(shop, sku)).isZero();
    fixture.assertInvariants(shop);
  }

  @Test
  void aggregateQuantityOverflowIs400() throws Exception {
    StockFixture.Shop shop = fixture.shop("ACTIVE");
    String shopId = fixture.tsfShopId(shop);
    UUID account = fixture.channelAccount(shop, "ACTIVE", "CONNECTED");
    UUID sku = fixture.sku(shop, 100);
    fixture.channelListing(shop, account, "L-a", sku, true);
    fixture.channelListing(shop, account, "L-b", sku, true);
    ObjectNode body =
        request(
            shopId, "chk-overflow", List.of("L-a", "L-b"), List.of(2_000_000_000, 2_000_000_000));
    HttpResponse<String> response = post("chk-overflow", body);
    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(CONTRACT.restErrors("error-bad-request", response.body())).isEmpty();
    assertThat(fixture.reserved(shop, sku)).isZero();
  }

  @Test
  void shadowBundleAndComponentWouldNotReserve() throws Exception {
    StockFixture.Shop shop = fixture.shop("ACTIVE");
    String shopId = fixture.tsfShopId(shop);
    UUID account = fixture.channelAccount(shop, "SHADOW", "CONNECTED");
    UUID component = fixture.sku(shop, 5);
    UUID bundle = fixture.bundle(shop, Map.of(component, 1));
    fixture.channelListing(shop, account, "L-bundle", bundle, true);
    fixture.channelListing(shop, account, "L-c", component, true);
    HttpResponse<String> response =
        post(
            "chk-shadow-bundle",
            request(shopId, "chk-shadow-bundle", List.of("L-bundle", "L-c"), List.of(4, 4)));
    assertThat(response.statusCode()).isEqualTo(201);
    JsonNode omsValue =
        fixture.inTenant(
            shop.tenant(),
            () ->
                JSON.readTree(
                    jdbc.queryForObject(
                        "SELECT oms_value::text FROM shadow_diff WHERE ref = ?",
                        String.class,
                        "chk-shadow-bundle")));
    assertThat(omsValue.path("would_reserve").asBoolean()).isFalse();
  }

  @Test
  void activeComponentlessBundleLineIsUnenforced() throws Exception {
    StockFixture.Shop shop = fixture.shop("ACTIVE");
    String shopId = fixture.tsfShopId(shop);
    UUID account = fixture.channelAccount(shop, "ACTIVE", "CONNECTED");
    UUID componentless = fixture.componentlessBundle(shop);
    UUID sku = fixture.sku(shop, 10);
    fixture.channelListing(shop, account, "L-empty", componentless, true);
    fixture.channelListing(shop, account, "L-ok", sku, true);
    int reservedBefore = fixture.reserved(shop, sku);
    HttpResponse<String> response =
        post("chk-empty", request(shopId, "chk-empty", List.of("L-empty", "L-ok"), List.of(3, 2)));
    assertThat(response.statusCode()).isEqualTo(201);
    JsonNode body = JSON.readTree(response.body());
    assertThat(body.path("enforced").asBoolean()).isFalse();
    for (JsonNode item : body.path("items")) {
      String listing = item.path("listing_sku_id").asString();
      if ("L-empty".equals(listing)) {
        assertThat(item.path("enforced").asBoolean()).isFalse();
      } else {
        assertThat(item.path("enforced").asBoolean()).isTrue();
      }
    }
    assertThat(fixture.reserved(shop, sku)).isEqualTo(reservedBefore + 2);
    fixture.assertInvariants(shop);
  }

  @Test
  void shadowComponentlessBundleWouldNotReserve() throws Exception {
    StockFixture.Shop shop = fixture.shop("ACTIVE");
    String shopId = fixture.tsfShopId(shop);
    UUID account = fixture.channelAccount(shop, "SHADOW", "CONNECTED");
    UUID componentless = fixture.componentlessBundle(shop);
    fixture.channelListing(shop, account, "L-empty", componentless, true);
    HttpResponse<String> response =
        post("chk-sh-empty", request(shopId, "chk-sh-empty", "L-empty", 2));
    assertThat(response.statusCode()).isEqualTo(201);
    JsonNode omsValue =
        fixture.inTenant(
            shop.tenant(),
            () ->
                JSON.readTree(
                    jdbc.queryForObject(
                        "SELECT oms_value::text FROM shadow_diff WHERE ref = ?",
                        String.class,
                        "chk-sh-empty")));
    assertThat(omsValue.path("would_reserve").asBoolean()).isTrue();
    assertThat(omsValue.path("items").get(0).path("would_reserve").asBoolean()).isFalse();
  }

  @Test
  void qtyOutsideIntRangeIs400() throws Exception {
    StockFixture.Shop shop = fixture.shop("ACTIVE");
    ObjectNode body = request(fixture.tsfShopId(shop), "chk-big", "L-1", 1);
    ArrayNode items = (ArrayNode) body.get("items");
    ((ObjectNode) items.get(0)).put("qty", 1L << 40);
    HttpResponse<String> response = post("chk-big", body);
    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(CONTRACT.restErrors("error-bad-request", response.body())).isEmpty();
  }

  private HttpResponse<String> post(String idempotencyKey, ObjectNode body) throws Exception {
    return post(idempotencyKey, body, serviceToken());
  }

  private HttpResponse<String> post(String idempotencyKey, ObjectNode body, String bearer)
      throws Exception {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(reserveUri())
            .header("Content-Type", "application/json")
            .header("Idempotency-Key", idempotencyKey)
            .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
    if (bearer != null) {
      builder.header("Authorization", "Bearer " + bearer);
    }
    return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  private HttpResponse<String> delete(String reservationId) throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(
                URI.create(
                    "http://127.0.0.1:"
                        + port
                        + "/internal/v1/inventory/reservations/"
                        + reservationId))
            .header("Authorization", "Bearer " + serviceToken())
            .DELETE()
            .build();
    return HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  private URI reserveUri() {
    return URI.create("http://127.0.0.1:" + port + "/internal/v1/inventory/reservations");
  }

  private static String serviceToken() {
    return AuthTestSupport.token(
        "tsf-checkout",
        "shop",
        "ACTIVE",
        null,
        1,
        "oms-internal",
        Instant.now().plusSeconds(600),
        List.of(),
        "SERVICE");
  }

  private static ObjectNode request(String shopId, String checkoutId, String listingSku, int qty) {
    return request(shopId, checkoutId, List.of(listingSku), List.of(qty));
  }

  private static ObjectNode request(
      String shopId, String checkoutId, List<String> listings, List<Integer> qtys) {
    ObjectNode body = JSON.createObjectNode();
    body.put("checkout_id", checkoutId);
    body.put("tsf_shop_id", shopId);
    ArrayNode items = JSON.createArrayNode();
    for (int i = 0; i < listings.size(); i++) {
      ObjectNode item = JSON.createObjectNode();
      item.put("listing_sku_id", listings.get(i));
      item.put("qty", qtys.get(i));
      items.add(item);
    }
    body.set("items", items);
    return body;
  }
}
