package com.thaishopfun.oms.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sun.net.httpserver.HttpServer;
import com.thaishopfun.oms.auth.AuthTestSupport;
import com.thaishopfun.oms.tenant.TenantContext;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Acceptance coverage for T14. The webhook receiver dedupes by {@code event_id}. Lease evidence is
 * the {@code outbox lease} log line.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "oms.outbox.publisher-enabled=false",
      "oms.outbox.webhook-secret=test-outbox-secret",
      "oms.outbox.jitter-ratio=0.2",
      "spring.datasource.hikari.maximum-pool-size=12"
    })
class OutboxAcceptanceTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Pattern LEASE =
      Pattern.compile(
          "outbox lease instance=(\\S+) event_id=(\\S+) tenant_id=(\\S+) lease_until=(\\S+) attempts=(\\d+)");
  private static final ListAppender<ILoggingEvent> LOGS = new ListAppender<>();
  private static final List<Delivery> DELIVERIES = new CopyOnWriteArrayList<>();
  private static final Set<String> APPLIED = ConcurrentHashMap.newKeySet();

  private static volatile int forcedStatus = 202;
  private static volatile String retryAfter;
  private static volatile boolean dedupe;
  private static HttpServer webhook;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    AuthTestSupport.register(registry);
    registry.add(
        "oms.outbox.destination-url",
        () -> "http://127.0.0.1:" + webhook.getAddress().getPort() + "/internal/v1/oms-events");
  }

  @BeforeAll
  static void webhookAndLogs() throws IOException {
    webhook = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    webhook.createContext(
        "/internal/v1/oms-events",
        exchange -> {
          byte[] raw = exchange.getRequestBody().readAllBytes();
          String eventId = exchange.getRequestHeaders().getFirst("X-Event-Id");
          DELIVERIES.add(
              new Delivery(
                  eventId,
                  exchange.getRequestHeaders().getFirst("X-Signature"),
                  new String(raw, StandardCharsets.UTF_8)));
          int status = forcedStatus;
          if (dedupe && eventId != null && !APPLIED.add(eventId)) {
            status = 200;
          }
          if (retryAfter != null) {
            exchange.getResponseHeaders().set("Retry-After", retryAfter);
          }
          exchange.sendResponseHeaders(status, -1);
          exchange.close();
        });
    webhook.setExecutor(
        Executors.newCachedThreadPool(
            runnable -> {
              Thread thread = new Thread(runnable, "outbox-webhook");
              thread.setDaemon(true);
              return thread;
            }));
    webhook.start();
  }

  @Autowired private JdbcTemplate jdbc;
  @Autowired private PlatformTransactionManager transactions;
  @Autowired private OutboxAppender appender;
  @Autowired private OutboxPublisher publisher;
  @Autowired private OutboxHooks hooks;
  @LocalServerPort private int port;

  private RestClient client;

  @BeforeEach
  void reset() throws SQLException {
    forcedStatus = 202;
    retryAfter = null;
    dedupe = false;
    DELIVERIES.clear();
    APPLIED.clear();
    attachLogs();
    hooks.reset();
    TenantContext.clear();
    try (Connection admin = AuthTestSupport.admin();
        Statement statement = admin.createStatement()) {
      statement.execute("TRUNCATE TABLE outbox_event");
    }
    client = RestClient.builder().baseUrl("http://127.0.0.1:" + port).build();
  }

  @AfterEach
  void clearTenant() {
    hooks.reset();
    TenantContext.clear();
  }

  @Test
  void appendRequiresTheBusinessTransaction() {
    UUID tenantId = insertTenant();
    TenantContext.set(tenantId, null);
    try {
      assertThatThrownBy(() -> appender.append(stock(1)))
          .isInstanceOf(IllegalTransactionStateException.class);
    } finally {
      TenantContext.clear();
    }
    assertThat(count("SELECT count(*) FROM outbox_event WHERE tenant_id = ?", tenantId)).isZero();
  }

  @Test
  void killDuringBusinessTransactionLeavesNoOutboxRow() {
    UUID tenantId = UUID.randomUUID();
    UUID[] eventId = new UUID[1];
    TenantContext.set(tenantId, null);
    try {
      assertThatThrownBy(
              () ->
                  new TransactionTemplate(transactions)
                      .executeWithoutResult(
                          status -> {
                            insertTenantRow(tenantId);
                            eventId[0] = appender.append(stock(3));
                            throw new IllegalStateException("kill");
                          }))
          .isInstanceOf(IllegalStateException.class);
    } finally {
      TenantContext.clear();
    }
    assertThat(eventId[0]).isNotNull();
    assertThat(count("SELECT count(*) FROM outbox_event WHERE id = ?", eventId[0])).isZero();
    assertThat(count("SELECT count(*) FROM tenant WHERE id = ?", tenantId)).isZero();
  }

  @Test
  void rowsStayInsideTheTenantThatWroteThem() {
    UUID tenantA = insertTenant();
    UUID tenantB = insertTenant();
    UUID eventA = append(tenantA, stock(1));
    UUID eventB = append(tenantB, stock(2));

    TenantContext.set(tenantA, null);
    try {
      Integer visible =
          new TransactionTemplate(transactions)
              .execute(
                  status ->
                      jdbc.queryForObject("SELECT count(*) FROM outbox_event", Integer.class));
      Integer hidden =
          new TransactionTemplate(transactions)
              .execute(
                  status ->
                      jdbc.queryForObject(
                          "SELECT count(*) FROM outbox_event WHERE id = ?", Integer.class, eventB));
      assertThat(visible).isEqualTo(1);
      assertThat(hidden).isZero();
    } finally {
      TenantContext.clear();
    }
    TenantContext.clear();
    Integer none =
        new TransactionTemplate(transactions)
            .execute(
                status -> jdbc.queryForObject("SELECT count(*) FROM outbox_event", Integer.class));
    assertThat(none).isZero();

    assertThat(publisher.publishOnce(10, Duration.ofMinutes(5))).isEqualTo(2);
    assertThat(DELIVERIES).hasSize(2);
    assertThat(statusOf(eventA)).isEqualTo("SENT");
    assertThat(statusOf(eventB)).isEqualTo("SENT");
  }

  @Test
  void publishSignsTheBodyAndMarksSent() throws Exception {
    UUID tenantId = insertTenant();
    UUID eventId = append(tenantId, Map.of("available", 18, "phone", "0812341234"));
    assertThat(publisher.publishOnce()).isEqualTo(1);

    assertThat(DELIVERIES).hasSize(1);
    Delivery delivery = DELIVERIES.get(0);
    assertThat(delivery.eventId()).isEqualTo(eventId.toString());
    assertThat(delivery.signature()).startsWith("t=");
    String[] parts = delivery.signature().split(",", 2);
    long timestamp = Long.parseLong(parts[0].substring(2));
    String given = parts[1].substring("v1=".length());
    assertThat(given)
        .isEqualTo(OutboxHttpSender.sign("test-outbox-secret", timestamp, delivery.body()));
    assertThat(Math.abs(Instant.now().getEpochSecond() - timestamp)).isLessThan(300);
    JsonNode body = JSON.readTree(delivery.body());
    assertThat(body.path("event_id").asString()).isEqualTo(eventId.toString());
    assertThat(body.path("event_type").asString()).isEqualTo("stock.updated");
    assertThat(body.path("data").path("available").asInt()).isEqualTo(18);
    assertThat(statusOf(eventId)).isEqualTo("SENT");
    assertThat(sentAt(eventId)).isNotNull();
    assertThat(LOGS.list).noneMatch(event -> event.getFormattedMessage().contains("0812341234"));
  }

  @Test
  void threePublishersNeverHoldTheSameEvent() throws Exception {
    UUID tenantId = insertTenant();
    List<UUID> ids = new ArrayList<>();
    for (int i = 0; i < 30; i++) {
      ids.add(append(tenantId, Map.of("available", i)));
    }
    List<OutboxPublisher> publishers =
        List.of(
            publisher.withInstance(UUID.randomUUID()),
            publisher.withInstance(UUID.randomUUID()),
            publisher.withInstance(UUID.randomUUID()));
    ExecutorService pool = Executors.newFixedThreadPool(3);
    CyclicBarrier barrier = new CyclicBarrier(3);
    List<Future<?>> futures = new ArrayList<>();
    for (OutboxPublisher each : publishers) {
      futures.add(
          pool.submit(
              () -> {
                barrier.await(10, TimeUnit.SECONDS);
                while (each.publishOnce(5, Duration.ofMinutes(5)) > 0) {
                  // Claim the next free batch. SKIP LOCKED keeps the other two off these rows.
                }
                return null;
              }));
    }
    for (Future<?> future : futures) {
      future.get(60, TimeUnit.SECONDS);
    }
    pool.shutdown();

    assertThat(DELIVERIES).hasSize(30);
    assertThat(DELIVERIES.stream().map(Delivery::eventId).distinct()).hasSize(30);
    for (UUID id : ids) {
      assertThat(statusOf(id)).isEqualTo("SENT");
    }
    Map<String, List<String>> holders = leaseHolders();
    assertThat(holders).hasSize(30);
    assertThat(holders.values()).allSatisfy(instances -> assertThat(instances).hasSize(1));
  }

  @Test
  void crashBeforeAckIsDeliveredAfterTheLeaseExpires() {
    UUID tenantId = insertTenant();
    UUID eventId = append(tenantId, stock(5));
    hooks.crashBeforeSend(true);
    assertThatThrownBy(() -> publisher.publishOnce()).isInstanceOf(OutboxCrash.class);
    hooks.reset();

    assertThat(DELIVERIES).isEmpty();
    assertThat(statusOf(eventId)).isEqualTo("IN_FLIGHT");
    assertThat(leaseLogs()).hasSize(1);
    UUID other = UUID.randomUUID();
    assertThat(publisher.withInstance(other).publishOnce()).isZero();
    assertThat(leaseLogs()).hasSize(1);
    assertThat(DELIVERIES).isEmpty();

    expireLease(eventId);
    assertThat(publisher.withInstance(other).publishOnce()).isEqualTo(1);
    assertThat(DELIVERIES).hasSize(1);
    assertThat(statusOf(eventId)).isEqualTo("SENT");
    assertThat(attempts(eventId)).isEqualTo(2);
    assertThat(leaseHolders().get(eventId.toString())).hasSize(2);
  }

  @Test
  void crashAfterAckIsRedeliveredAndTheReceiverDedupes() {
    dedupe = true;
    UUID tenantId = insertTenant();
    UUID eventId = append(tenantId, Map.of("available", 9));
    hooks.crashAfterAck(true);
    assertThatThrownBy(() -> publisher.publishOnce()).isInstanceOf(OutboxCrash.class);
    hooks.reset();

    assertThat(DELIVERIES).hasSize(1);
    assertThat(APPLIED).containsExactly(eventId.toString());
    assertThat(statusOf(eventId)).isEqualTo("IN_FLIGHT");

    expireLease(eventId);
    assertThat(publisher.publishOnce()).isEqualTo(1);
    assertThat(DELIVERIES).hasSize(2);
    assertThat(DELIVERIES.get(1).eventId()).isEqualTo(eventId.toString());
    assertThat(APPLIED).containsExactly(eventId.toString());
    assertThat(statusOf(eventId)).isEqualTo("SENT");
  }

  @Test
  void http500FollowsTheBackoffLadderUntilDead() {
    forcedStatus = 500;
    UUID tenantId = insertTenant();
    UUID eventId = append(tenantId, stock(1));
    Duration[] minimum =
        new Duration[] {
          Duration.ofSeconds(20),
          Duration.ofSeconds(90),
          Duration.ofMinutes(7),
          Duration.ofMinutes(22),
          Duration.ofMinutes(45),
          Duration.ofHours(2),
          Duration.ofHours(4)
        };
    Duration[] maximum =
        new Duration[] {
          Duration.ofSeconds(45),
          Duration.ofSeconds(160),
          Duration.ofMinutes(13),
          Duration.ofMinutes(40),
          Duration.ofMinutes(80),
          Duration.ofHours(4),
          Duration.ofHours(8)
        };
    for (int attempt = 0; attempt < 7; attempt++) {
      Instant started = Instant.now();
      assertThat(publisher.publishOnce()).isEqualTo(1);
      Row row = read(eventId);
      assertThat(row.status()).isEqualTo("PENDING");
      assertThat(row.attempts()).isEqualTo(attempt + 1);
      Duration delay = Duration.between(started, row.nextAttemptAt().toInstant());
      assertThat(delay).isBetween(minimum[attempt], maximum[attempt]);
      expireBackoff(eventId);
    }
    assertThat(publisher.publishOnce()).isEqualTo(1);
    Row dead = read(eventId);
    assertThat(dead.status()).isEqualTo("DEAD");
    assertThat(dead.attempts()).isEqualTo(8);
    assertThat(dead.nextAttemptAt()).isNull();
    assertThat(publisher.publishOnce()).isZero();
    assertThat(DELIVERIES).hasSize(8);
    assertThat(LOGS.list).anyMatch(event -> event.getFormattedMessage().contains("status=DEAD"));
  }

  @Test
  void http429HonorsRetryAfterWhenItIsLaterThanTheSchedule() {
    forcedStatus = 429;
    retryAfter = "50";
    UUID tenantId = insertTenant();
    UUID eventId = append(tenantId, stock(1));
    Instant started = Instant.now();
    publisher.publishOnce();
    Duration delay = Duration.between(started, read(eventId).nextAttemptAt().toInstant());
    assertThat(delay).isBetween(Duration.ofSeconds(49), Duration.ofSeconds(55));
    assertThat(read(eventId).status()).isEqualTo("PENDING");
  }

  @Test
  void shortRetryAfterStillWaitsForTheSchedule() {
    forcedStatus = 429;
    retryAfter = "5";
    UUID tenantId = insertTenant();
    UUID eventId = append(tenantId, stock(1));
    Instant started = Instant.now();
    publisher.publishOnce();
    Duration delay = Duration.between(started, read(eventId).nextAttemptAt().toInstant());
    assertThat(delay).isBetween(Duration.ofSeconds(20), Duration.ofSeconds(45));
  }

  @Test
  void http400IsDeadImmediatelyAnd408IsRetried() {
    forcedStatus = 400;
    UUID tenantId = insertTenant();
    UUID deadId = append(tenantId, Map.of("phone", "0812341234", "available", 1));
    publisher.publishOnce();
    assertThat(read(deadId).status()).isEqualTo("DEAD");
    assertThat(read(deadId).attempts()).isEqualTo(1);
    assertThat(publisher.publishOnce()).isZero();
    assertThat(DELIVERIES).hasSize(1);
    assertThat(LOGS.list).allMatch(event -> !event.getFormattedMessage().contains("0812341234"));

    DELIVERIES.clear();
    forcedStatus = 408;
    UUID retryId = append(tenantId, stock(2));
    Instant started = Instant.now();
    publisher.publishOnce();
    Row row = read(retryId);
    assertThat(row.status()).isEqualTo("PENDING");
    assertThat(Duration.between(started, row.nextAttemptAt().toInstant()))
        .isBetween(Duration.ofSeconds(20), Duration.ofSeconds(45));
  }

  @Test
  void adminRetryResetsADeadEventAndOtherTenantsCannotSeeIt() throws Exception {
    String shop = "shop-admin-" + UUID.randomUUID();
    String owner = "owner-" + UUID.randomUUID();
    String ownerToken = token(owner, shop, "OWNER");
    JsonNode me = get("/api/v1/me", ownerToken);
    UUID tenantId = UUID.fromString(me.path("tenant").path("id").asString());

    forcedStatus = 400;
    UUID eventId = append(tenantId, Map.of("phone", "0812341234", "available", 7));
    publisher.publishOnce();
    assertThat(statusOf(eventId)).isEqualTo("DEAD");

    JsonNode listed = get("/api/v1/outbox", ownerToken);
    assertThat(listed.path("events").size()).isEqualTo(1);
    assertThat(listed.path("events").get(0).path("id").asString()).isEqualTo(eventId.toString());
    assertThat(listed.path("events").get(0).path("aggregate_type").asString())
        .isEqualTo("inventory");
    assertThat(listed.path("events").get(0).path("event_type").asString())
        .isEqualTo("stock.updated");
    assertThat(listed.toString()).doesNotContain("0812341234");

    JsonNode retried = post("/api/v1/outbox/" + eventId + "/retry", ownerToken, 200);
    assertThat(retried.path("status").asString()).isEqualTo("PENDING");
    assertThat(retried.path("attempts").asInt()).isZero();
    assertThat(get("/api/v1/outbox", ownerToken).path("events").size()).isZero();
    assertThat(
            post("/api/v1/outbox/" + eventId + "/retry", ownerToken, 409).path("error").asString())
        .isEqualTo("CONFLICT");

    String audit = auditAfter(eventId);
    assertThat(audit).contains("outbox.retry");
    assertThat(audit).doesNotContain("0812341234");

    forcedStatus = 202;
    assertThat(publisher.publishOnce()).isEqualTo(1);
    assertThat(statusOf(eventId)).isEqualTo("SENT");

    String staffToken = token("staff-" + UUID.randomUUID(), shop, "STAFF");
    get("/api/v1/me", staffToken);
    forcedStatus = 400;
    UUID staffTarget = append(tenantId, stock(1));
    publisher.publishOnce();
    assertThat(
            post("/api/v1/outbox/" + staffTarget + "/retry", staffToken, 403)
                .path("error")
                .asString())
        .isEqualTo("FORBIDDEN");

    String otherToken =
        token("other-" + UUID.randomUUID(), "shop-other-" + UUID.randomUUID(), "OWNER");
    get("/api/v1/me", otherToken);
    assertThat(
            post("/api/v1/outbox/" + staffTarget + "/retry", otherToken, 404)
                .path("error")
                .asString())
        .isEqualTo("NOT_FOUND");
    assertThat(get("/api/v1/outbox", otherToken).path("events").size()).isZero();

    RestClient.ResponseSpec unauthorized =
        client.post().uri("/api/v1/outbox/" + staffTarget + "/retry").retrieve();
    assertThatThrownBy(unauthorized::toBodilessEntity).hasMessageContaining("401");
  }

  private static void attachLogs() {
    // Spring Boot resets Logback while the context starts, so attach after that.
    if (!LOGS.isStarted()) {
      LOGS.start();
    }
    Logger publisherLog = (Logger) LoggerFactory.getLogger(OutboxPublisher.class);
    Logger storeLog = (Logger) LoggerFactory.getLogger(OutboxStore.class);
    publisherLog.setLevel(Level.INFO);
    storeLog.setLevel(Level.INFO);
    if (!publisherLog.isAttached(LOGS)) {
      publisherLog.addAppender(LOGS);
    }
    if (!storeLog.isAttached(LOGS)) {
      storeLog.addAppender(LOGS);
    }
    LOGS.list.clear();
  }

  private static String token(String userId, String shopId, String role) {
    return AuthTestSupport.token(
        userId,
        shopId,
        "ACTIVE",
        Instant.now().plus(30, ChronoUnit.DAYS),
        1,
        "oms",
        Instant.now().plusSeconds(600),
        List.of("oms"),
        role);
  }

  private JsonNode get(String path, String token) throws Exception {
    String body =
        client
            .get()
            .uri(path)
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
            .retrieve()
            .body(String.class);
    return JSON.readTree(body);
  }

  private JsonNode post(String path, String token, int expected) throws Exception {
    return client
        .post()
        .uri(path)
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
        .exchange(
            (request, response) -> {
              String raw = new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8);
              assertThat(response.getStatusCode().value()).isEqualTo(expected);
              assertThat(response.getHeaders().getFirst("X-Trace-Id")).isNotBlank();
              return JSON.readTree(raw);
            });
  }

  private UUID insertTenant() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.set(tenantId, null);
    try {
      new TransactionTemplate(transactions)
          .executeWithoutResult(status -> insertTenantRow(tenantId));
    } finally {
      TenantContext.clear();
    }
    return tenantId;
  }

  private void insertTenantRow(UUID tenantId) {
    jdbc.update(
        """
        INSERT INTO tenant (
          id, name, tsf_shop_id, membership_tier, entitlement_status, ent_ver
        ) VALUES (?, ?, ?, 'PRO', 'ACTIVE', 1)
        """,
        tenantId,
        "Shop",
        "shop-" + tenantId);
  }

  private UUID append(UUID tenantId, Object data) {
    TenantContext.set(tenantId, null);
    try {
      return new TransactionTemplate(transactions)
          .execute(
              status ->
                  appender.append(
                      data instanceof OutboxDraft draft
                          ? draft
                          : OutboxDraft.of("inventory", "sku-1", "stock.updated", data)));
    } finally {
      TenantContext.clear();
    }
  }

  private static OutboxDraft stock(int available) {
    return OutboxDraft.of("inventory", "sku-1", "stock.updated", Map.of("available", available));
  }

  private void expireLease(UUID eventId) {
    execute(
        "UPDATE outbox_event SET lease_until = now() - interval '1 second' WHERE id = ?", eventId);
  }

  private void expireBackoff(UUID eventId) {
    execute(
        "UPDATE outbox_event SET next_attempt_at = now() - interval '1 second' WHERE id = ?",
        eventId);
  }

  private static void execute(String sql, UUID eventId) {
    try (Connection admin = AuthTestSupport.admin();
        PreparedStatement statement = admin.prepareStatement(sql)) {
      statement.setObject(1, eventId);
      statement.executeUpdate();
    } catch (SQLException ex) {
      throw new IllegalStateException(ex);
    }
  }

  private static int count(String sql, UUID id) {
    try (Connection admin = AuthTestSupport.admin();
        PreparedStatement statement = admin.prepareStatement(sql)) {
      statement.setObject(1, id);
      try (ResultSet rows = statement.executeQuery()) {
        rows.next();
        return rows.getInt(1);
      }
    } catch (SQLException ex) {
      throw new IllegalStateException(ex);
    }
  }

  private static String statusOf(UUID id) {
    return read(id).status();
  }

  private static int attempts(UUID id) {
    return read(id).attempts();
  }

  private static OffsetDateTime sentAt(UUID id) {
    try (Connection admin = AuthTestSupport.admin();
        PreparedStatement statement =
            admin.prepareStatement("SELECT sent_at FROM outbox_event WHERE id = ?")) {
      statement.setObject(1, id);
      try (ResultSet rows = statement.executeQuery()) {
        rows.next();
        return rows.getObject(1, OffsetDateTime.class);
      }
    } catch (SQLException ex) {
      throw new IllegalStateException(ex);
    }
  }

  private static Row read(UUID id) {
    try (Connection admin = AuthTestSupport.admin();
        PreparedStatement statement =
            admin.prepareStatement(
                "SELECT status, attempts, next_attempt_at FROM outbox_event WHERE id = ?")) {
      statement.setObject(1, id);
      try (ResultSet rows = statement.executeQuery()) {
        if (!rows.next()) {
          return new Row(null, 0, null);
        }
        return new Row(
            rows.getString("status"),
            rows.getInt("attempts"),
            rows.getObject("next_attempt_at", OffsetDateTime.class));
      }
    } catch (SQLException ex) {
      throw new IllegalStateException(ex);
    }
  }

  private static String auditAfter(UUID eventId) {
    try (Connection admin = AuthTestSupport.admin();
        PreparedStatement statement =
            admin.prepareStatement(
                """
                SELECT action || ' ' || "before"::text || ' ' || "after"::text
                FROM audit_log WHERE entity_id = ?
                """)) {
      statement.setString(1, eventId.toString());
      try (ResultSet rows = statement.executeQuery()) {
        return rows.next() ? rows.getString(1) : "";
      }
    } catch (SQLException ex) {
      throw new IllegalStateException(ex);
    }
  }

  private static List<String> leaseLogs() {
    List<String> lines = new ArrayList<>();
    for (ILoggingEvent event : LOGS.list) {
      if (event.getFormattedMessage().startsWith("outbox lease ")) {
        lines.add(event.getFormattedMessage());
      }
    }
    return lines;
  }

  private static Map<String, List<String>> leaseHolders() {
    Map<String, List<String>> holders = new java.util.LinkedHashMap<>();
    for (String line : leaseLogs()) {
      Matcher matcher = LEASE.matcher(line);
      assertThat(matcher.matches()).isTrue();
      holders.computeIfAbsent(matcher.group(2), ignored -> new ArrayList<>()).add(matcher.group(1));
    }
    return holders;
  }

  private record Delivery(String eventId, String signature, String body) {}

  private record Row(String status, int attempts, OffsetDateTime nextAttemptAt) {}
}
