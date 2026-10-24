package com.thaishopfun.oms.stockpush;

import static org.assertj.core.api.Assertions.assertThat;

import com.thaishopfun.mocktsf.MockTsfApplication;
import com.thaishopfun.mocktsf.contract.ContractResponses;
import com.thaishopfun.mocktsf.events.ReceivedEventStore;
import com.thaishopfun.oms.auth.AuthTestSupport;
import com.thaishopfun.oms.invariant.VerifyInvariants;
import com.thaishopfun.oms.listing.ChannelListingRepository;
import com.thaishopfun.oms.outbox.OutboxPublisher;
import com.thaishopfun.oms.stock.ReservationEngine;
import com.thaishopfun.oms.stock.ReserveItem;
import com.thaishopfun.oms.stock.StockFixture;
import com.thaishopfun.oms.stock.StockFixture.Shop;
import com.thaishopfun.oms.stock.StockOwner;
import com.thaishopfun.oms.tenant.TenantContext;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@ActiveProfiles("test")
@SpringBootTest(
    properties = {
      "oms.stock.push.enabled=false",
      "oms.outbox.publisher-enabled=false",
      "oms.inbox.worker-enabled=false",
      "spring.datasource.hikari.maximum-pool-size=12",
      "oms.outbox.webhook-secret=dev-outbox-webhook-secret-local-only"
    })
@DirtiesContext
@VerifyInvariants(scope = VerifyInvariants.Scope.STOCK_ONLY)
class StockPushAcceptanceTest {
  private static ConfigurableApplicationContext mock;

  @org.junit.jupiter.api.AfterAll
  static void stopMock() {
    if (mock != null) {
      mock.close();
      mock = null;
    }
  }

  private static synchronized void startMock() {
    if (mock == null) {
      SpringApplication app = MockTsfApplication.application();
      mock =
          app.run(
              "--server.port=0",
              "--server.address=127.0.0.1",
              "--spring.main.register-shutdown-hook=false",
              "--spring.main.banner-mode=off");
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    startMock();
    AuthTestSupport.register(registry);
    registry.add(
        "oms.outbox.destination-url",
        () ->
            "http://127.0.0.1:"
                + mock.getEnvironment().getProperty("local.server.port")
                + "/internal/v1/oms-events");
  }

  @Autowired JdbcTemplate jdbc;
  @Autowired PlatformTransactionManager transactions;
  @Autowired StockPushService service;
  @Autowired StockPushProperties properties;
  @Autowired ReservationEngine engine;
  @Autowired ChannelListingRepository mappings;
  @Autowired OutboxPublisher publisher;
  @Autowired com.thaishopfun.oms.stock.StockAvailability availability;
  @Autowired com.thaishopfun.oms.outbox.OutboxAppender appender;
  @Autowired MeterRegistry meters;
  @Autowired JsonMapper json;
  private StockFixture fixture;
  private final List<Catalog> catalogs = new ArrayList<>();

  @BeforeEach
  void setup() {
    fixture = new StockFixture(jdbc, transactions);
  }

  @AfterEach
  void reset() {
    properties.setBatchSize(500);
    TenantContext.clear();
    // Do not leave this suite's 1,000 historical outbox versions ahead of later delivery tests.
    for (Catalog c : catalogs) {
      events(c); // Contract-validate every emitted batch, including older versions.
      sql(c, "DELETE FROM outbox_event WHERE event_type = 'stock.updated'");
    }
  }

  private record Catalog(Shop shop, UUID account, UUID sku) {}

  private Catalog catalog(String mode) {
    Shop shop = fixture.shop("ACTIVE");
    UUID account = fixture.tsfChannelAccount(shop, mode, "CONNECTED");
    UUID sku = fixture.sku(shop, 10000);
    fixture.channelListing(shop, account, "component", sku, true);
    Catalog result = new Catalog(shop, account, sku);
    catalogs.add(result);
    return result;
  }

  private <T> T as(Shop shop, Supplier<T> work) {
    TenantContext.set(shop.tenant(), null);
    try {
      return work.get();
    } finally {
      TenantContext.clear();
    }
  }

  private StockOwner reserve(Catalog c, int qty) {
    StockOwner owner = StockOwner.order("stock-push-" + UUID.randomUUID());
    assertThat(
            as(
                    c.shop(),
                    () ->
                        engine.reserve(
                            owner,
                            List.of(ReserveItem.of(c.sku(), qty)),
                            "reserve-" + UUID.randomUUID()))
                .reserved())
        .isTrue();
    return owner;
  }

  private void sql(Catalog c, String statement, Object... args) {
    fixture.runInTenant(c.shop().tenant(), () -> jdbc.update(statement, args));
  }

  private List<JsonNode> events(Catalog c) {
    return fixture.inTenant(
        c.shop().tenant(),
        () ->
            jdbc.query(
                """
        SELECT payload::text FROM outbox_event WHERE event_type = 'stock.updated'
          AND aggregate_id = ? ORDER BY created_at, id
        """,
                (rs, n) -> {
                  String payload = rs.getString(1);
                  mock.getBean(ContractResponses.class).requireInboundEnvelope(payload);
                  return json.readTree(payload);
                },
                c.account().toString()));
  }

  private List<JsonNode> latestItems(Catalog c) {
    return fixture.inTenant(
        c.shop().tenant(),
        () ->
            jdbc.queryForObject(
                """
        SELECT payload::text FROM outbox_event WHERE event_type = 'stock.updated'
          AND aggregate_id = ? ORDER BY created_at DESC, id DESC LIMIT 1
        """,
                (rs, n) -> {
                  String payload = rs.getString(1);
                  mock.getBean(ContractResponses.class).requireInboundEnvelope(payload);
                  List<JsonNode> items = new ArrayList<>();
                  json.readTree(payload).path("data").path("items").forEach(items::add);
                  return items;
                },
                c.account().toString()));
  }

  private int flush(Catalog c, boolean full) {
    return service.flush(c.shop().tenant(), c.account(), full);
  }

  private void mature(Catalog c) {
    sql(
        c,
        "UPDATE stock_push_pending SET dirty_since = clock_timestamp() - interval '3 seconds' WHERE dirty_since IS NOT NULL");
  }

  @Test
  void fiveReservationsInOneSecondProduceOneLatestAbsoluteEvent() throws Exception {
    Catalog c = catalog("ACTIVE");
    flush(c, true);
    long earlier = latestItems(c).get(0).path("stock_version").asLong();
    int before = events(c).size();
    Instant start = Instant.now();
    for (int i = 0; i < 5; i++) reserve(c, 1);
    assertThat(Duration.between(start, Instant.now())).isLessThan(Duration.ofSeconds(1));
    assertThat(flush(c, false)).isZero();
    Thread.sleep(2100);
    assertThat(flush(c, false)).isEqualTo(1);
    assertThat(events(c)).hasSize(before + 1);
    assertThat(latestItems(c)).hasSize(1);
    assertThat(latestItems(c).get(0).path("available").asInt()).isEqualTo(9995);
    assertThat(latestItems(c).get(0).path("stock_version").asLong()).isGreaterThan(earlier);
    assertThat(flush(c, true)).isZero();
  }

  @Test
  void sellingAComponentAlsoPushesBundleAndIncreasesItsVersion() {
    Catalog c = catalog("ACTIVE");
    UUID bundle = fixture.bundle(c.shop(), Map.of(c.sku(), 2));
    fixture.channelListing(c.shop(), c.account(), "bundle", bundle, true);
    flush(c, true);
    long previous =
        latestItems(c).stream()
            .filter(i -> i.path("listing_sku_id").asString().equals("bundle"))
            .findFirst()
            .orElseThrow()
            .path("stock_version")
            .asLong();
    StockOwner owner = reserve(c, 3);
    as(c.shop(), () -> engine.consume(owner, "consume-" + UUID.randomUUID()));
    mature(c);
    assertThat(flush(c, false)).isEqualTo(1);
    assertThat(latestItems(c)).hasSize(2);
    var bundleItem =
        latestItems(c).stream()
            .filter(i -> i.path("listing_sku_id").asString().equals("bundle"))
            .findFirst()
            .orElseThrow();
    assertThat(bundleItem.path("available").asInt()).isEqualTo(4998);
    assertThat(bundleItem.path("stock_version").asLong()).isGreaterThan(previous);
  }

  @Test
  void pausedBlocksDebounceAndFullPushThenUnpauseCatchesUpOnce() {
    Catalog c = catalog("ACTIVE");
    sql(c, "UPDATE channel_account SET stock_sync_paused = true WHERE id = ?", c.account());
    reserve(c, 2);
    mature(c);
    assertThat(flush(c, false)).isZero();
    assertThat(flush(c, true)).isZero();
    assertThat(events(c)).isEmpty();
    sql(c, "UPDATE channel_account SET stock_sync_paused = false WHERE id = ?", c.account());
    assertThat(flush(c, true)).isEqualTo(1);
    assertThat(latestItems(c).get(0).path("available").asInt()).isEqualTo(9998);
    assertThat(flush(c, true)).isZero();
  }

  @Test
  void modesDisconnectedRemovedAndUnmappedAreEvaluatedAtBuildTime() {
    Catalog c = catalog("ACTIVE");
    fixture.channelListing(c.shop(), c.account(), "not-controlled", c.sku(), false);
    fixture.channelListing(c.shop(), c.account(), "removed", c.sku(), true);
    fixture.channelListing(c.shop(), c.account(), "unmapped", c.sku(), true, false);
    sql(c, "UPDATE channel_listing SET removed_at = now() WHERE external_sku_id = 'removed'");
    sql(c, "UPDATE channel_account SET mode = 'OBSERVE' WHERE id = ?", c.account());
    assertThat(flush(c, true)).isZero();
    sql(c, "UPDATE channel_account SET mode = 'SHADOW' WHERE id = ?", c.account());
    assertThat(flush(c, true)).isZero();
    assertThat(events(c)).isEmpty();
    assertThat(
            fixture.inTenant(
                c.shop().tenant(),
                () ->
                    jdbc.queryForObject(
                        "SELECT count(*) FROM shadow_diff WHERE kind = 'STOCK_PUSH' AND channel_value IS NULL",
                        Long.class)))
        .isEqualTo(2);
    assertThat(flush(c, true)).isZero();
    assertThat(
            fixture.inTenant(
                c.shop().tenant(),
                () ->
                    jdbc.queryForObject(
                        "SELECT count(*) FROM shadow_diff WHERE kind = 'STOCK_PUSH'", Long.class)))
        .isEqualTo(2);
    sql(c, "UPDATE channel_account SET mode = 'CONTROL' WHERE id = ?", c.account());
    assertThat(flush(c, true)).isEqualTo(1);
    assertThat(latestItems(c)).hasSize(1);
    assertThat(latestItems(c).get(0).path("listing_sku_id").asString()).isEqualTo("component");
    sql(
        c,
        "UPDATE channel_account SET mode = 'ACTIVE', status = 'DISCONNECTED' WHERE id = ?",
        c.account());
    reserve(c, 1);
    mature(c);
    assertThat(flush(c, false)).isZero();
    sql(c, "UPDATE channel_account SET status = 'CONNECTED' WHERE id = ?", c.account());
    assertThat(flush(c, true)).isEqualTo(1);
    assertThat(latestItems(c)).hasSize(2);
  }

  @Test
  void manualMappingAutoMappingReviveRemappingAndUnmappingAreCaptured() throws Exception {
    Catalog c = catalog("ACTIVE");
    fixture.channelListing(c.shop(), c.account(), "stub", c.sku(), false, false);
    UUID stub =
        fixture.inTenant(
            c.shop().tenant(),
            () ->
                jdbc.queryForObject(
                    "SELECT id FROM channel_listing WHERE external_sku_id = 'stub'", UUID.class));
    fixture.runInTenant(c.shop().tenant(), () -> mappings.putManualMapping(stub, c.sku()));
    assertThat(flush(c, false)).isZero();
    Thread.sleep(2100);
    assertThat(flush(c, false)).isEqualTo(1);
    assertThat(latestItems(c)).hasSize(2);
    fixture.runInTenant(c.shop().tenant(), () -> mappings.clearMapping(stub));
    reserve(c, 1);
    mature(c);
    flush(c, false);
    assertThat(latestItems(c)).hasSize(1);
    UUID other = fixture.sku(c.shop(), 25);
    fixture.runInTenant(c.shop().tenant(), () -> mappings.putManualMapping(stub, other));
    mature(c);
    flush(c, false);
    assertThat(latestItems(c).get(0).path("available").asInt()).isEqualTo(25);
    String code =
        fixture.inTenant(
            c.shop().tenant(),
            () ->
                jdbc.queryForObject("SELECT sku_code FROM sku WHERE id = ?", String.class, other));
    fixture.runInTenant(
        c.shop().tenant(),
        () -> mappings.upsertFromChannel(c.shop().tenant(), c.account(), "auto", code, "auto"));
    mature(c);
    assertThat(flush(c, false)).isEqualTo(1);
    sql(c, "UPDATE channel_listing SET removed_at = now() WHERE external_sku_id = 'auto'");
    mature(c);
    assertThat(flush(c, false)).isZero();
    fixture.runInTenant(
        c.shop().tenant(),
        () -> mappings.upsertFromChannel(c.shop().tenant(), c.account(), "auto", code, "auto"));
    mature(c);
    assertThat(flush(c, false)).isEqualTo(1);
    assertThat(latestItems(c).get(0).path("listing_sku_id").asString()).isEqualTo("auto");
  }

  @Test
  void durablePendingSurvivesANewWorkerAndTwoInstancesCannotDuplicate() throws Exception {
    Catalog c = catalog("ACTIVE");
    reserve(c, 1);
    mature(c);
    StockPushService restarted =
        new StockPushService(jdbc, availability, appender, properties, meters, json, transactions);
    assertThat(restarted).isNotSameAs(service);
    var executor = Executors.newFixedThreadPool(2);
    try {
      var a = executor.submit(() -> service.flush(c.shop().tenant(), c.account(), false));
      var b = executor.submit(() -> restarted.flush(c.shop().tenant(), c.account(), false));
      assertThat(a.get(20, TimeUnit.SECONDS) + b.get(20, TimeUnit.SECONDS)).isEqualTo(1);
    } finally {
      executor.shutdownNow();
    }
    assertThat(events(c)).hasSize(1);
    assertThat(flush(c, true)).isZero();
  }

  @Test
  void fullPushFindsNeverPushedAndRepairsCrashBeforeCapture() {
    Catalog c = catalog("ACTIVE");
    assertThat(flush(c, true)).isEqualTo(1);
    reserve(c, 2);
    // Simulate a process death after the stock commit, before AFTER_COMMIT capture.
    sql(c, "UPDATE stock_push_pending SET dirty_since = NULL, changed_at = NULL");
    assertThat(flush(c, false)).isZero();
    assertThat(flush(c, true)).isEqualTo(1);
    assertThat(latestItems(c).get(0).path("available").asInt()).isEqualTo(9998);
    assertThat(flush(c, true)).isZero();
  }

  @Test
  void versionsIncreaseAcrossOneThousandRandomStockChangesIncludingBundle() {
    Catalog c = catalog("ACTIVE");
    UUID bundle = fixture.bundle(c.shop(), Map.of(c.sku(), 20000));
    fixture.channelListing(c.shop(), c.account(), "bundle", bundle, true);
    Random random = new Random(15);
    long last = 0;
    for (int i = 0; i < 1000; i++) {
      reserve(c, 1 + random.nextInt(3));
      assertThat(flush(c, true)).isEqualTo(1);
      for (JsonNode item : latestItems(c)) {
        assertThat(item.path("stock_version").asLong()).isGreaterThan(last);
      }
      last = latestItems(c).get(0).path("stock_version").asLong();
    }
  }

  @Test
  void tenantsAreIsolatedAndNullSellerSkuUsesMappedCodeAndMetric() {
    Catalog a = catalog("ACTIVE");
    Catalog b = catalog("ACTIVE");
    double fallback = meters.counter("oms.stock.push.seller_sku_fallback").count();
    assertThat(service.flush(a.shop().tenant(), b.account(), true)).isZero();
    properties.setBatchSize(1);
    fixture.channelListing(a.shop(), a.account(), "second", a.sku(), false);
    assertThat(flush(a, true)).isEqualTo(2);
    assertThat(events(b)).isEmpty();
    assertThat(flush(b, true)).isEqualTo(1);
    assertThat(meters.counter("oms.stock.push.seller_sku_fallback").count())
        .isEqualTo(fallback + 3);
    for (Catalog c : List.of(a, b)) {
      for (JsonNode event : events(c)) {
        assertThat(event.path("tsf_shop_id").asString()).isEqualTo(fixture.tsfShopId(c.shop()));
        assertThat(event.path("data").path("items").get(0).path("seller_sku").asString())
            .isEqualTo("SKU-" + c.sku());
      }
    }
  }

  @Test
  void twoHundredChangesReachRealContractValidatingMockWithP95UnderTenSeconds() throws Exception {
    Catalog c = catalog("ACTIVE");
    List<Instant> commits = new ArrayList<>();
    for (int i = 0; i < 200; i++) {
      reserve(c, 1);
      commits.add(Instant.now());
    }
    Instant deadline = Instant.now().plusSeconds(8);
    while (events(c).isEmpty() && Instant.now().isBefore(deadline)) {
      flush(c, false);
      if (events(c).isEmpty()) Thread.sleep(100);
    }
    assertThat(events(c)).hasSize(1);
    String eventId = events(c).get(0).path("event_id").asString();
    Instant sendDeadline = Instant.now().plusSeconds(4);
    while (mock.getBean(ReceivedEventStore.class).all().stream()
            .noneMatch(e -> e.eventId().equals(eventId))
        && Instant.now().isBefore(sendDeadline)) publisher.publishOnce();
    var received =
        mock.getBean(ReceivedEventStore.class).all().stream()
            .filter(e -> e.eventId().equals(eventId))
            .findFirst()
            .orElseThrow();
    Instant sentAt =
        fixture
            .inTenant(
                c.shop().tenant(),
                () ->
                    jdbc.queryForObject(
                        "SELECT sent_at FROM outbox_event WHERE id = ?",
                        OffsetDateTime.class,
                        UUID.fromString(eventId)))
            .toInstant();
    List<Long> lag =
        commits.stream().map(t -> Duration.between(t, sentAt).toMillis()).sorted().toList();
    long p95 = lag.get((int) Math.ceil(lag.size() * .95) - 1);
    assertThat(p95).isLessThan(10000);
    assertThat(Duration.between(commits.get(0), Instant.parse(received.receivedAt())).toMillis())
        .isLessThan(10000);
    assertThat(meters.find("oms.stock.push.propagation_lag").timer().count()).isPositive();
    System.out.printf(
        "T15 propagation: samples=%d p95_ms=%d max_ms=%d%n",
        lag.size(), p95, lag.get(lag.size() - 1));
    java.nio.file.Files.writeString(
        java.nio.file.Path.of("target/stock-push-lag.json"),
        "{\"samples\":200,\"p95_ms\":" + p95 + ",\"max_ms\":" + lag.get(lag.size() - 1) + "}");
  }
}
