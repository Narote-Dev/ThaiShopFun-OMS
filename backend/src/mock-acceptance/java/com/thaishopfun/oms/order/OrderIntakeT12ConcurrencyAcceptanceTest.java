package com.thaishopfun.oms.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.thaishopfun.mocktsf.MockTsfApplication;
import com.thaishopfun.mocktsf.OmsEndpoint;
import com.thaishopfun.mocktsf.idp.TokenIssuer;
import com.thaishopfun.oms.auth.AuthTestSupport;
import com.thaishopfun.oms.auth.UuidV7;
import com.thaishopfun.oms.inbox.InboxWorker;
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
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** T12 concurrency: parallel intake with expiry sweeper and injected deadlocks. */
@ActiveProfiles("test")
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"spring.main.allow-bean-definition-overriding=true"})
@Import({StockTestConfig.class, OrderIntakeT12ScenariosAcceptanceTest.IntakeTestConfig.class})
class OrderIntakeT12ConcurrencyAcceptanceTest {

  private static final String ISSUER = "http://mock-tsf.test/tsf-idp";
  private static final String INBOX_SECRET = "dev-inbox-hmac-secret";
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final HttpClient HTTP =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

  private static ConfigurableApplicationContext mock;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    startMock();
    AuthTestSupport.register(registry);
    int mockPort = mockPort();
    registry.add("oms.security.issuer", () -> ISSUER);
    registry.add(
        "oms.security.jwks-uri",
        () -> "http://127.0.0.1:" + mockPort + "/tsf-idp/.well-known/jwks.json");
    registry.add("oms.security.internal-client-ids", () -> "tsf,tsf-checkout");
    registry.add("oms.inbox.hmac-secrets", () -> INBOX_SECRET);
    registry.add("oms.inbox.jitter-ratio", () -> "0");
    registry.add("oms.outbox.publisher-enabled", () -> "false");
  }

  @AfterAll
  static void stopMock() {
    if (mock != null) {
      mock.close();
    }
  }

  @LocalServerPort private int port;

  @Autowired InboxWorker worker;
  @Autowired JdbcTemplate jdbc;
  @Autowired PlatformTransactionManager transactions;
  @Autowired StockExpiryJob expiryJob;
  @Autowired FaultHooks faults;

  StockFixture fixture;

  @BeforeEach
  void setup() throws Exception {
    mock.getBean(OmsEndpoint.class).setBaseUrl("http://127.0.0.1:" + port);
    fixture = new StockFixture(jdbc, transactions);
    faults.reset();
    try (Connection admin = AuthTestSupport.admin();
        var statement = admin.createStatement()) {
      statement.execute("SET session_replication_role = replica");
      statement.execute(
          "TRUNCATE TABLE outbox_event, inbox_event, order_status_history, order_line, "
              + "order_recipient, sales_order, stock_reservation, inventory_ledger, inventory, "
              + "idempotency_key, reconciliation_issue, shadow_diff CASCADE");
      statement.execute("SET session_replication_role = DEFAULT");
    }
  }

  @AfterEach
  void clearTenant() {
    TenantContext.clear();
    faults.reset();
  }

  @Test
  @Timeout(value = 3, unit = TimeUnit.MINUTES)
  void fiftyParallelCreatedEventsWithSweeperAndDeadlockInjection() throws Exception {
    StockFixture.Shop shop = fixture.shop("ACTIVE");
    String shopId = fixture.tsfShopId(shop);
    UUID account = fixture.tsfChannelAccount(shop, "ACTIVE", "CONNECTED");
    UUID sku = fixture.sku(shop, 200);
    fixture.channelListing(shop, account, "L-conc", sku, true);

    for (int i = 0; i < 50; i++) {
      String externalOrderId = "TSF-CONC-" + i + "-" + UUID.randomUUID();
      ObjectNode created =
          OrderIntakeScenarioSupport.orderCreated(
              JSON, externalOrderId, shopId, UuidV7.generate().toString(), "COD", "L-conc", 1, 1);
      ingest(created);
    }

    AtomicInteger workerPasses = new AtomicInteger();
    ExecutorService pool = Executors.newFixedThreadPool(4);
    try {
      Future<?> sweeper =
          pool.submit(
              () -> {
                for (int pass = 0; pass < 40; pass++) {
                  expiryJob.runOnce();
                  Thread.sleep(25);
                }
                return null;
              });
      List<Callable<Void>> workers = new ArrayList<>();
      for (int w = 0; w < 4; w++) {
        workers.add(
            () -> {
              while (true) {
                if (workerPasses.incrementAndGet() % 11 == 0) {
                  faults.failNext(Fault.DEADLOCK);
                }
                int processed = worker.processAvailable(3);
                if (processed == 0
                    && fixture.inTenant(
                            shop.tenant(),
                            () ->
                                jdbc.queryForObject("SELECT count(*) FROM sales_order", Long.class))
                        >= 50) {
                  break;
                }
                if (processed == 0) {
                  Thread.sleep(10);
                }
              }
              return null;
            });
      }
      List<Future<Void>> results = pool.invokeAll(workers, 120, TimeUnit.SECONDS);
      for (Future<Void> result : results) {
        result.get(30, TimeUnit.SECONDS);
      }
      sweeper.get(120, TimeUnit.SECONDS);
    } finally {
      pool.shutdownNow();
    }

    assertThat(worker.processAvailable(20)).isZero();
    assertThat(
            fixture.inTenant(
                shop.tenant(),
                () -> jdbc.queryForObject("SELECT count(*) FROM sales_order", Long.class)))
        .isEqualTo(50);
    assertThat(fixture.reserved(shop, sku)).isEqualTo(50);
    assertThat(faults.fired()).isGreaterThan(0);
    fixture.assertInvariants(shop);
  }

  private void ingest(ObjectNode event) throws Exception {
    byte[] body = JSON.writeValueAsBytes(event);
    String eventId = event.path("event_id").asString();
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/internal/v1/events"))
            .header("Content-Type", "application/json")
            .header("X-Event-Id", eventId)
            .header("X-Signature", sign(INBOX_SECRET, now(), body))
            .header("Authorization", "Bearer " + tsfToken())
            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
            .build();
    HttpResponse<String> response =
        HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    assertThat(response.statusCode()).isEqualTo(202);
  }

  private static String tsfToken() {
    return mock.getBean(TokenIssuer.class).tsfServiceToken();
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
            "--mock.oms-base-url=http://127.0.0.1:9",
            "--spring.main.banner-mode=off",
            "--spring.main.register-shutdown-hook=false");
  }

  private static int mockPort() {
    String port = mock.getEnvironment().getProperty("local.server.port");
    if (port == null || port.isBlank() || "0".equals(port)) {
      throw new IllegalStateException("mock-tsf did not bind a port");
    }
    return Integer.parseInt(port);
  }
}
