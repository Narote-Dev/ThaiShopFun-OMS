package com.thaishopfun.oms.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.thaishopfun.mocktsf.OmsEndpoint;
import com.thaishopfun.oms.auth.AuthTestSupport;
import com.thaishopfun.oms.auth.UuidV7;
import com.thaishopfun.oms.inbox.InboxWorker;
import com.thaishopfun.oms.stock.OrderIntakeFaultTestConfig;
import com.thaishopfun.oms.stock.StockExpiryJob;
import com.thaishopfun.oms.stock.StockFixture;
import com.thaishopfun.oms.tenant.TenantContext;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Parallel intake across tenants with expiry sweeper and a one-shot 40P01 on {@link
 * OrderIntakeHooks#beforeEngineWrite()}.
 */
@ActiveProfiles("test")
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"spring.main.allow-bean-definition-overriding=true"})
@Import({
  OrderIntakeT12ScenariosAcceptanceTest.IntakeTestConfig.class,
  OrderIntakeFaultTestConfig.class
})
class OrderIntakeT12ConcurrencyAcceptanceTest {

  private enum StepKind {
    CHECKOUT,
    CREATED,
    FOLLOWUP
  }

  private record Op(int orderIndex, StepKind kind) {}

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
    registry.add("oms.stock.checkout-ttl", () -> "45s");
  }

  @LocalServerPort private int port;

  @Autowired InboxWorker worker;
  @Autowired JdbcTemplate jdbc;
  @Autowired PlatformTransactionManager transactions;
  @Autowired StockExpiryJob expiryJob;

  StockFixture fixture;

  @BeforeEach
  void setup() throws Exception {
    OrderIntakeMockRuntime.mock().getBean(OmsEndpoint.class).setBaseUrl("http://127.0.0.1:" + port);
    fixture = new StockFixture(jdbc, transactions);
    OrderIntakeFaultTestConfig.injectDeadlockOnce.set(false);
    OrderIntakeFaultTestConfig.injectDeadlockConsumed.set(false);
    try (Connection admin = AuthTestSupport.admin();
        var statement = admin.createStatement()) {
      statement.execute("SET lock_timeout = '10s'");
      statement.execute("SET statement_timeout = '30s'");
      statement.execute("SET session_replication_role = replica");
      statement.execute(
          "TRUNCATE TABLE outbox_event, inbox_event, order_status_history, order_line, "
              + "order_recipient, sales_order, stock_reservation, inventory_ledger, inventory, "
              + "idempotency_key, reconciliation_issue, shadow_diff CASCADE");
      statement.execute("SET session_replication_role = DEFAULT");
    }
  }

  @AfterAll
  static void stopMockTsf() {
    OrderIntakeMockRuntime.stopMock();
  }

  @AfterEach
  void clearTenant() {
    TenantContext.clear();
    OrderIntakeFaultTestConfig.injectDeadlockOnce.set(false);
  }

  @Test
  @Timeout(value = 8, unit = TimeUnit.MINUTES, threadMode = ThreadMode.SEPARATE_THREAD)
  void fiftyOrdersTenTenantsParallelIntakeWithSweeperAndDeadlockRetry() throws Exception {
    record ShopCtx(StockFixture.Shop shop, String shopId, UUID account, UUID skuA, UUID skuB) {}

    List<ShopCtx> shops = new ArrayList<>();
    for (int t = 0; t < 10; t++) {
      StockFixture.Shop shop = fixture.shop("ACTIVE");
      String shopId = fixture.tsfShopId(shop);
      UUID account = fixture.tsfChannelAccount(shop, "ACTIVE", "CONNECTED");
      UUID skuA = fixture.sku(shop, 4);
      UUID skuB = fixture.sku(shop, 4);
      fixture.channelListing(shop, account, "L-a", skuA, true);
      fixture.channelListing(shop, account, "L-b", skuB, true);
      shops.add(new ShopCtx(shop, shopId, account, skuA, skuB));
    }

    record OrderPlan(
        ShopCtx ctx,
        String externalOrderId,
        boolean reverseLines,
        boolean cancel,
        String[] reservationIdHolder) {}

    List<OrderPlan> plans = new ArrayList<>();
    for (int i = 0; i < 50; i++) {
      ShopCtx ctx = shops.get(i % 10);
      String externalOrderId = "TSF-CONC-" + i + "-" + UUID.randomUUID();
      boolean reverseLines = i % 2 == 1;
      boolean cancel = i % 5 == 4;
      plans.add(new OrderPlan(ctx, externalOrderId, reverseLines, cancel, new String[1]));
    }

    List<Op> ops = shuffledValidOps(new Random(47), 50);

    OrderIntakeFaultTestConfig.injectDeadlockOnce.set(true);
    OrderIntakeFaultTestConfig.injectDeadlockConsumed.set(false);

    AtomicBoolean stopWorkers = new AtomicBoolean(false);
    ExecutorService pool = Executors.newFixedThreadPool(5);
    try {
      Future<?> sweeper =
          pool.submit(
              () -> {
                while (!stopWorkers.get()) {
                  expiryJob.runOnce();
                  Thread.sleep(50);
                }
                return null;
              });
      List<Future<?>> workers = new ArrayList<>();
      for (int w = 0; w < 4; w++) {
        workers.add(
            pool.submit(
                () -> {
                  while (true) {
                    if (stopWorkers.get() && !inboxNeedsWork()) {
                      return null;
                    }
                    worker.processAvailable(4);
                    Thread.sleep(25);
                  }
                }));
      }

      for (Op op : ops) {
        OrderPlan plan = plans.get(op.orderIndex());
        ShopCtx ctx = plan.ctx();
        switch (op.kind()) {
          case CHECKOUT -> {
            JsonNode checkout =
                checkoutTwoLines(
                    "chk-" + op.orderIndex() + "-" + UUID.randomUUID(),
                    ctx.shopId(),
                    plan.reverseLines() ? "L-b" : "L-a",
                    plan.reverseLines() ? "L-a" : "L-b");
            if (checkout != null) {
              plan.reservationIdHolder()[0] = checkout.path("reservation_id").asString();
            } else {
              plan.reservationIdHolder()[0] = UuidV7.generate().toString();
            }
          }
          case CREATED ->
              ingest(
                  OrderIntakeScenarioSupport.orderCreatedTwoLines(
                      JSON,
                      plan.externalOrderId(),
                      ctx.shopId(),
                      plan.reservationIdHolder()[0] != null
                          ? plan.reservationIdHolder()[0]
                          : UuidV7.generate().toString(),
                      "COD",
                      plan.reverseLines() ? "L-b" : "L-a",
                      plan.reverseLines() ? "L-a" : "L-b",
                      1,
                      1,
                      1));
          case FOLLOWUP -> {
            if (plan.cancel()) {
              ingest(
                  OrderIntakeScenarioSupport.orderCancelled(
                      JSON, plan.externalOrderId(), ctx.shopId(), 2));
            } else {
              ingest(
                  OrderIntakeScenarioSupport.orderPaid(
                      JSON, plan.externalOrderId(), ctx.shopId(), 2));
            }
          }
        }
      }

      stopWorkers.set(true);
      for (Future<?> workerFuture : workers) {
        workerFuture.get(30, TimeUnit.SECONDS);
      }
      sweeper.get(30, TimeUnit.SECONDS);
    } finally {
      stopWorkers.set(true);
      pool.shutdownNow();
      pool.awaitTermination(30, TimeUnit.SECONDS);
    }

    drainInbox();

    assertThat(OrderIntakeFaultTestConfig.injectDeadlockConsumed).isTrue();
    assertThat(OrderIntakeFaultTestConfig.injectDeadlockOnce).isFalse();
    assertThat(
            jdbc.queryForObject("SELECT count(*) FROM inbox_event WHERE attempts > 1", Long.class))
        .isPositive();

    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM inbox_event WHERE status <> 'PROCESSED'", Long.class))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM inbox_event WHERE status IN ('FAILED', 'DEAD')", Long.class))
        .isZero();
    assertThat(jdbc.queryForObject("SELECT count(*) FROM sales_order", Long.class)).isEqualTo(50);

    long outOfStockOrders =
        jdbc.queryForObject(
            "SELECT count(*) FROM sales_order WHERE hold_reason = 'OUT_OF_STOCK'", Long.class);
    assertThat(outOfStockOrders).isGreaterThan(0);

    for (ShopCtx ctx : shops) {
      fixture.inTenant(
          ctx.shop().tenant(),
          () -> {
            assertThat(
                    jdbc.queryForObject(
                        """
                        SELECT count(*) FROM inventory i
                        WHERE i.on_hand - COALESCE((
                          SELECT sum(r.qty) FROM stock_reservation r
                          WHERE r.tenant_id = i.tenant_id AND r.sku_id = i.sku_id
                            AND r.warehouse_id = i.warehouse_id AND r.status = 'ACTIVE'
                        ), 0) < 0
                        """,
                        Long.class))
                .isZero();
            long activeRows =
                jdbc.queryForObject(
                    """
                    SELECT count(*) FROM stock_reservation
                    WHERE owner_type = 'ORDER' AND status = 'ACTIVE'
                    """,
                    Long.class);
            long reservedSum =
                jdbc.queryForObject(
                    """
                    SELECT COALESCE(sum(qty), 0) FROM stock_reservation
                    WHERE owner_type = 'ORDER' AND status = 'ACTIVE'
                    """,
                    Long.class);
            assertThat(activeRows).isEqualTo(reservedSum);
            assertThat(
                    fixture.reserved(ctx.shop(), ctx.skuA())
                        + fixture.reserved(ctx.shop(), ctx.skuB()))
                .isEqualTo(reservedSum);
            fixture.assertInvariants(ctx.shop());
            return null;
          });
    }
  }

  private static List<Op> shuffledValidOps(Random random, int orderCount) {
    List<Op> ops = new ArrayList<>(orderCount * 3);
    int[] next = new int[orderCount];
    while (ops.size() < orderCount * 3) {
      List<Integer> eligible = new ArrayList<>();
      for (int i = 0; i < orderCount; i++) {
        if (next[i] < 3) {
          eligible.add(i);
        }
      }
      int pick = eligible.get(random.nextInt(eligible.size()));
      StepKind kind =
          switch (next[pick]) {
            case 0 -> StepKind.CHECKOUT;
            case 1 -> StepKind.CREATED;
            default -> StepKind.FOLLOWUP;
          };
      ops.add(new Op(pick, kind));
      next[pick]++;
    }
    return ops;
  }

  private void drainInbox() throws Exception {
    for (int pass = 0; pass < 120; pass++) {
      worker.processAvailable(20);
      expiryJob.runOnce();
      if (!inboxNeedsWork()) {
        return;
      }
      Thread.sleep(25);
    }
  }

  private boolean inboxNeedsWork() {
    Long remaining =
        jdbc.queryForObject(
            "SELECT count(*) FROM inbox_event WHERE status <> 'PROCESSED'", Long.class);
    return remaining != null && remaining > 0;
  }

  /** Returns checkout body on 201; null when checkout reserve is out of stock (409). */
  private JsonNode checkoutTwoLines(
      String checkoutId, String shopId, String listingA, String listingB) throws Exception {
    ObjectNode body = JSON.createObjectNode();
    body.put("checkout_id", checkoutId);
    body.put("tsf_shop_id", shopId);
    ArrayNode items = JSON.createArrayNode();
    ObjectNode itemA = JSON.createObjectNode();
    itemA.put("listing_sku_id", listingA);
    itemA.put("qty", 1);
    items.add(itemA);
    ObjectNode itemB = JSON.createObjectNode();
    itemB.put("listing_sku_id", listingB);
    itemB.put("qty", 1);
    items.add(itemB);
    body.set("items", items);
    HttpResponse<String> response =
        HTTP.send(
            HttpRequest.newBuilder(
                    URI.create(
                        "http://127.0.0.1:"
                            + OrderIntakeMockRuntime.mockPort()
                            + "/control/checkout/reservations"))
                .timeout(HTTP_TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    assertThat(response.statusCode()).isEqualTo(200);
    JsonNode report = JSON.readTree(response.body());
    int omsStatus = report.path("oms_status").asInt();
    if (omsStatus == 409) {
      return null;
    }
    assertThat(omsStatus).isEqualTo(201);
    return JSON.readTree(report.path("oms_body").asString());
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
                    + OrderIntakeMockRuntime.mock()
                        .getBean(com.thaishopfun.mocktsf.idp.TokenIssuer.class)
                        .tsfServiceToken())
            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
            .build();
    HttpResponse<String> response =
        HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    assertThat(response.statusCode()).isEqualTo(202);
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
