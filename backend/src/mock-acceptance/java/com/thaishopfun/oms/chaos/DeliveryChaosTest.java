package com.thaishopfun.oms.chaos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.thaishopfun.mocktsf.MockTsfApplication;
import com.thaishopfun.mocktsf.OmsEndpoint;
import com.thaishopfun.mocktsf.events.ReceivedEventStore;
import com.thaishopfun.oms.auth.AuthTestSupport;
import com.thaishopfun.oms.chaos.ChaosFaultProxy.Fault;
import com.thaishopfun.oms.chaos.ChaosOrderCreatedHandler.Plan;
import com.thaishopfun.oms.inbox.InboxEntitlementPolicy;
import com.thaishopfun.oms.inbox.InboxHandlerRegistry;
import com.thaishopfun.oms.inbox.InboxIngestService;
import com.thaishopfun.oms.inbox.InboxProperties;
import com.thaishopfun.oms.inbox.InboxWorker;
import com.thaishopfun.oms.order.ReconciliationIssueRepository;
import com.thaishopfun.oms.outbox.ChaosOutboxHooks;
import com.thaishopfun.oms.outbox.OutboxAppender;
import com.thaishopfun.oms.outbox.OutboxCrash;
import com.thaishopfun.oms.outbox.OutboxDraft;
import com.thaishopfun.oms.outbox.OutboxPublisher;
import com.thaishopfun.oms.outbox.OutboxStore;
import com.thaishopfun.oms.tenant.TenantContext;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * T14B delivery chaos suite: 1,000 events in each direction through the real HTTP paths, with
 * duplicates, reordering, crashes, restarts, and receiver faults. At-least-once delivery is allowed
 * to duplicate; the assertions are that nothing is lost and the end effect equals one delivery per
 * event.
 *
 * <p>Every fault is chosen up front from {@link #SEED} (override with {@code -Dchaos.seed=<long>})
 * and is keyed by event id and attempt number, never by timing. Waits are bounded polls on database
 * state. Backoffs and orphaned leases are fast-forwarded only while no worker or publisher is
 * running.
 *
 * <p>Tagged {@code chaos}: excluded from the default build, run by {@code -Pmock-acceptance,chaos}.
 */
@Tag("chaos")
@ActiveProfiles({"test", "chaos"})
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "oms.inbox.worker-enabled=false",
      "oms.inbox.jitter-ratio=0",
      "oms.inbox.batch-size=5",
      "oms.inbox.handler-timeout=1s",
      "oms.inbox.lease=6s",
      "oms.outbox.publisher-enabled=false",
      "oms.outbox.jitter-ratio=0",
      "oms.outbox.batch-size=5",
      "oms.outbox.http-timeout=1s",
      "oms.outbox.lease=15s",
      "spring.datasource.hikari.maximum-pool-size=16"
    })
@Import(DeliveryChaosTest.ChaosConfig.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DeliveryChaosTest {

  static final long SEED = Long.getLong("chaos.seed", 20260930L);

  private static final org.slf4j.Logger log = LoggerFactory.getLogger(DeliveryChaosTest.class);

  private static final String ISSUER = "http://mock-tsf.test/tsf-idp";
  private static final String INBOX_SECRET = "dev-inbox-hmac-secret";
  private static final String OUTBOX_SECRET = "dev-outbox-webhook-secret-local-only";
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final HttpClient HTTP =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

  private static final int EVENTS = 1000;
  private static final int TENANTS = 4;
  private static final int FLEET_SIZE = 3;
  private static final int MAX_ROUNDS = 12;
  private static final Duration ROUND_LIMIT = Duration.ofSeconds(60);
  private static final Duration POLL = Duration.ofMillis(20);

  /** Section 4.4 retry ladder, restated so the suite checks the contract, not the code. */
  private static final Duration[] LADDER = {
    Duration.ofSeconds(30),
    Duration.ofMinutes(2),
    Duration.ofMinutes(10),
    Duration.ofMinutes(30),
    Duration.ofHours(1),
    Duration.ofHours(3),
    Duration.ofHours(6)
  };

  private static final Pattern LEASE =
      Pattern.compile(
          "outbox lease instance=(\\S+) event_id=(\\S+) tenant_id=(\\S+) lease_until=(\\S+) attempts=(\\d+)");
  private static final ListAppender<ILoggingEvent> LOGS = new ListAppender<>();

  private static ConfigurableApplicationContext mock;
  private static ChaosFaultProxy proxy;
  private static final ChaosReport REPORT = new ChaosReport(SEED);

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) throws IOException {
    startMock();
    if (proxy == null) {
      proxy = new ChaosFaultProxy(mockUri("/internal/v1/oms-events"));
    }
    AuthTestSupport.registerDatabase(registry);
    int mockPort = mockPort();
    registry.add("oms.security.issuer", () -> ISSUER);
    registry.add(
        "oms.security.jwks-uri",
        () -> "http://127.0.0.1:" + mockPort + "/tsf-idp/.well-known/jwks.json");
    registry.add("oms.security.audience", () -> "oms");
    registry.add("oms.security.internal-audience", () -> "oms-internal");
    registry.add("oms.security.internal-client-ids", () -> "tsf");
    registry.add("oms.inbox.hmac-secrets", () -> INBOX_SECRET);
    registry.add("oms.outbox.destination-url", () -> proxy.uri().toString());
    registry.add("oms.outbox.webhook-secret", () -> OUTBOX_SECRET);
    registry.add("oms.order-intake.enabled", () -> "false");
  }

  @AfterAll
  static void stopHarness() {
    if (proxy != null) {
      proxy.close();
    }
    if (mock != null) {
      mock.close();
    }
  }

  @LocalServerPort private int port;

  @Autowired private InboxWorker worker;
  @Autowired private InboxProperties inboxProperties;
  @Autowired private InboxHandlerRegistry registry;
  @Autowired private InboxEntitlementPolicy policy;
  @Autowired private ReconciliationIssueRepository reconciliation;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private PlatformTransactionManager transactions;
  @Autowired private JsonMapper jsonMapper;
  @Autowired private MeterRegistry meters;
  @Autowired private OutboxAppender appender;
  @Autowired private OutboxPublisher publisher;
  @Autowired private ChaosOrderCreatedHandler handler;
  @Autowired private ChaosOutboxHooks hooks;

  @BeforeEach
  void reset() throws SQLException {
    log.info("chaos seed {} (reproduce with -Dchaos.seed={})", SEED, SEED);
    mock.getBean(OmsEndpoint.class).setBaseUrl("http://127.0.0.1:" + port);
    handler.reset();
    hooks.reset();
    proxy.reset();
    TenantContext.clear();
    try (Connection admin = AuthTestSupport.admin();
        Statement statement = admin.createStatement()) {
      statement.execute("TRUNCATE TABLE inbox_event, outbox_event");
    }
    attachLogs();
  }

  @AfterEach
  void clearTenant() {
    TenantContext.clear();
  }

  // ---------------------------------------------------------------------------------------------
  // Inbound: TSF -> OMS inbox
  // ---------------------------------------------------------------------------------------------

  @Test
  @Order(1)
  void inboundThousandEventsAreProcessedExactlyOnce() throws Exception {
    long started = System.nanoTime();
    Random random = new Random(SEED);
    double mismatchesBefore = meters.counter(InboxIngestService.MISMATCH_METRIC).count();

    // Step 1: Tenants come from membership.changed over the real HMAC path.
    List<String> shops = provisionShops("chaos-in-shop-" + SEED, TENANTS);

    // Step 2: 1,000 order.created events, one aggregate each, spread over the tenants.
    List<ObjectNode> events = new ArrayList<>();
    for (int i = 0; i < EVENTS; i++) {
      events.add(orderCreated(inboundId(i), shops.get(i % TENANTS), i));
    }

    // Step 3: Handler faults by event id: rollback once, rollback twice, or kill the worker.
    List<Integer> faultOrder = permutation(EVENTS, random);
    Map<Plan, Integer> planned = new LinkedHashMap<>();
    planned.put(Plan.THROW_ONCE, 100);
    planned.put(Plan.THROW_TWICE, 20);
    planned.put(Plan.KILL_WORKER, 6);
    int cursor = 0;
    for (Map.Entry<Plan, Integer> entry : planned.entrySet()) {
      for (int k = 0; k < entry.getValue(); k++) {
        handler.plan(inboundId(faultOrder.get(cursor++)), entry.getKey());
      }
    }

    // Step 4: Delivery units. Every event is in exactly one primary unit, then 100 are resent.
    List<Integer> sendOrder = permutation(EVENTS, random);
    List<Unit> units = new ArrayList<>();
    int validDeliveries = 0;
    for (int k = 0; k < 50; k++) {
      int times = 2 + random.nextInt(4);
      units.add(new Unit(UnitKind.REPEAT, List.of(sendOrder.get(k)), times));
      validDeliveries += times;
    }
    for (int batch = 0; batch < 20; batch++) {
      units.add(new Unit(UnitKind.SHUFFLE, sendOrder.subList(50 + batch * 10, 60 + batch * 10), 1));
      validDeliveries += 10;
    }
    for (int k = 250; k < EVENTS; k++) {
      units.add(new Unit(UnitKind.SEND, List.of(sendOrder.get(k)), 1));
      validDeliveries++;
    }
    List<Integer> resendOrder = permutation(EVENTS, random);
    for (int k = 0; k < 100; k++) {
      units.add(new Unit(UnitKind.SEND, List.of(resendOrder.get(k)), 1));
      validDeliveries++;
    }
    // Step 5: Rejections. 10 bad signatures, 5 bad signatures replaying a real id, 10 stale.
    List<ObjectNode> rejectedOnly = new ArrayList<>();
    for (int k = 0; k < 10; k++) {
      ObjectNode bad = orderCreated("chaos-in-bad-" + SEED + "-" + k, shops.get(k % TENANTS), k);
      rejectedOnly.add(bad);
      units.add(new Unit(UnitKind.BAD_SIGNATURE, List.of(-rejectedOnly.size()), 1));
    }
    for (int k = 0; k < 10; k++) {
      ObjectNode stale =
          orderCreated("chaos-in-stale-" + SEED + "-" + k, shops.get(k % TENANTS), k);
      rejectedOnly.add(stale);
      units.add(new Unit(UnitKind.STALE, List.of(-rejectedOnly.size()), 1));
    }
    List<Integer> replayOrder = permutation(EVENTS, random);
    for (int k = 0; k < 5; k++) {
      units.add(new Unit(UnitKind.BAD_SIGNATURE, List.of(replayOrder.get(k)), 1));
    }
    Collections.shuffle(units, random);

    // Step 6: Three worker processes run while TSF is still sending.
    ChaosFleet fleet =
        new ChaosFleet(
            "chaos-inbox",
            FLEET_SIZE,
            () -> {
              InboxWorker fresh = freshInboxWorker();
              return fresh::processAvailable;
            },
            ChaosOrderCreatedHandler.WorkerKilled.class);
    fleet.start();
    Map<Integer, Integer> statuses = new HashMap<>();
    List<String> unexpectedStatuses = new ArrayList<>();
    int restartAt = units.size() / 2;
    try {
      for (int u = 0; u < units.size(); u++) {
        if (u == restartAt) {
          // Step 7: Planned restart by send count: stop every worker mid-run, start fresh ones.
          fleet.restartAll();
        }
        for (JsonNode row : deliver(units.get(u), events, rejectedOnly)) {
          int status = row.path("http_status").asInt();
          statuses.merge(status, 1, Integer::sum);
          if (status != 202 && status != 200 && status != 401) {
            unexpectedStatuses.add(row.toString());
          }
        }
        fleet.replaceDead();
      }

      // Step 8: Rounds. Drain, stop every process, skip backoffs and dead leases, start again.
      Rounds rounds = drain(fleet, this::claimableInbox, () -> processedInbox() == EVENTS);
      long wallMs = Duration.ofNanos(System.nanoTime() - started).toMillis();

      // Step 9: Nothing lost, nothing left behind, nothing applied twice.
      assertThat(unexpectedStatuses).isEmpty();
      assertThat(fleet.errors()).isEmpty();
      assertThat(statuses.getOrDefault(202, 0)).isEqualTo(EVENTS);
      assertThat(statuses.getOrDefault(200, 0)).isEqualTo(validDeliveries - EVENTS);
      assertThat(statuses.getOrDefault(401, 0)).isEqualTo(25);
      assertThat(inboxStatusCounts()).containsExactly(Map.entry("PROCESSED", (long) EVENTS));

      Map<String, Long> effects = effectsByEvent();
      assertThat(effects).hasSize(EVENTS);
      assertThat(effects.values()).allMatch(count -> count == 1L);
      for (int i = 0; i < EVENTS; i++) {
        assertThat(effects).containsKey(inboundId(i));
      }
      assertThat(
              count(
                  """
                  SELECT count(*) FROM audit_log a
                  JOIN inbox_event e ON e.event_id = a.entity_id AND e.tenant_id = a.tenant_id
                  WHERE a.action = 'CHAOS_TEST' AND e.status = 'PROCESSED'
                    AND a.entity_id LIKE ?
                  """,
                  INBOUND_ID_PATTERN))
          .isEqualTo(EVENTS);
      assertThat(
              count(
                  "SELECT count(DISTINCT tenant_id) FROM inbox_event WHERE event_type = 'order.created'"))
          .isEqualTo(TENANTS);

      // Step 10: Rejected requests left no row. A replayed id with a bad signature is still one.
      for (ObjectNode rejected : rejectedOnly) {
        assertThat(
                count(
                    "SELECT count(*) FROM inbox_event WHERE event_id = ?",
                    rejected.path("event_id").asString()))
            .isZero();
      }
      assertThat(count("SELECT count(*) FROM inbox_event WHERE event_type = 'order.created'"))
          .isEqualTo(EVENTS);

      // Step 11: Every planned fault fired exactly as often as planned, and the retry succeeded.
      assertThat(handler.handlerErrors()).isEqualTo(100 + 20 * 2);
      assertThat(handler.kills()).isEqualTo(6);
      assertThat(fleet.deaths()).isEqualTo(6);
      assertThat(meters.counter(InboxIngestService.MISMATCH_METRIC).count())
          .isEqualTo(mismatchesBefore);
      long attempts =
          count(
              "SELECT COALESCE(sum(attempts), 0) FROM inbox_event WHERE event_type = 'order.created'");

      Map<String, Integer> retries = new LinkedHashMap<>();
      retries.put("handler_error_rolled_back", handler.handlerErrors());
      retries.put("worker_killed_mid_transaction", handler.kills());
      retries.put("backoff_skipped_after_failure", rounds.skippedBackoffs());
      retries.put("orphaned_lease_skipped", rounds.skippedLeases());
      retries.put("claims_beyond_first", (int) (attempts - EVENTS));
      retries.put("handler_calls_beyond_first", handler.totalCalls() - EVENTS);
      Map<String, Object> section =
          ChaosReport.direction(EVENTS, statuses.get(202), validDeliveries, retries, wallMs);
      section.put(
          "max_attempts",
          count("SELECT max(attempts) FROM inbox_event WHERE event_type = 'order.created'"));
      section.put("rejected_401", statuses.getOrDefault(401, 0));
      section.put("effects_applied", effects.size());
      section.put("tenants", TENANTS);
      section.put("worker_processes_started", fleet.started());
      section.put("drain_rounds", rounds.count());
      REPORT.put("inbound", section);
      assertThat(statuses.getOrDefault(200, 0)).isPositive();
    } finally {
      fleet.stopAll();
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Outbound: OMS outbox -> mock-tsf receiver
  // ---------------------------------------------------------------------------------------------

  @Test
  @Order(2)
  void outboundThousandEventsReachTheReceiverOnce() throws Exception {
    long started = System.nanoTime();
    Random random = new Random(SEED ^ 0x5DEECE66DL);
    ReceivedEventStore received = mock.getBean(ReceivedEventStore.class);
    received.clear();

    // Step 1: Tenants, then the fault plan by event index. Ids exist only after append.
    List<UUID> tenants = new ArrayList<>();
    for (String shop : provisionShops("chaos-out-shop-" + SEED, TENANTS)) {
      tenants.add(tenantId(shop));
    }
    Map<Integer, List<Fault>> proxyPlan = new HashMap<>();
    Set<Integer> crashBefore = new HashSet<>();
    Set<Integer> crashAfter = new HashSet<>();
    List<Integer> faultOrder = permutation(EVENTS, random);
    int cursor = 0;
    for (int k = 0; k < 20; k++) {
      crashBefore.add(faultOrder.get(cursor++));
    }
    for (int k = 0; k < 20; k++) {
      crashAfter.add(faultOrder.get(cursor++));
    }
    Map<Fault, Integer> single = new LinkedHashMap<>();
    single.put(Fault.HTTP_503, 20);
    single.put(Fault.HTTP_503_RETRY_AFTER, 20);
    single.put(Fault.HTTP_500, 20);
    single.put(Fault.HTTP_429_RETRY_AFTER, 30);
    single.put(Fault.RESET, 20);
    single.put(Fault.RESET_AFTER_FORWARD, 20);
    single.put(Fault.TIMEOUT_AFTER_FORWARD, 10);
    for (Map.Entry<Fault, Integer> entry : single.entrySet()) {
      for (int k = 0; k < entry.getValue(); k++) {
        proxyPlan.put(faultOrder.get(cursor++), List.of(entry.getKey()));
      }
    }
    for (int k = 0; k < 20; k++) {
      proxyPlan.put(faultOrder.get(cursor++), List.of(Fault.HTTP_503, Fault.HTTP_429_RETRY_AFTER));
    }
    Map<Fault, Integer> expectedFired = new LinkedHashMap<>();
    for (Fault fault : Fault.values()) {
      expectedFired.put(fault, single.getOrDefault(fault, 0));
    }
    expectedFired.merge(Fault.HTTP_503, 20, Integer::sum);
    expectedFired.merge(Fault.HTTP_429_RETRY_AFTER, 20, Integer::sum);
    int expectedDuplicates =
        crashAfter.size()
            + single.get(Fault.RESET_AFTER_FORWARD)
            + single.get(Fault.TIMEOUT_AFTER_FORWARD);

    // Step 2: 100 committed business transactions of 10 events, plus 5 that roll back.
    Set<Integer> rollbackAt = new HashSet<>(permutation(105, random).subList(0, 5));
    ChaosFleet fleet =
        new ChaosFleet(
            "chaos-outbox",
            FLEET_SIZE,
            () -> {
              OutboxPublisher fresh = publisher.withInstance(UUID.randomUUID());
              return fresh::publishOnce;
            },
            OutboxCrash.class);
    fleet.start();
    Map<String, Integer> indexById = new HashMap<>();
    Set<String> rolledBack = new HashSet<>();
    try {
      int next = 0;
      for (int tx = 0; tx < 105; tx++) {
        if (tx == 52) {
          // Step 3: Planned restart by transaction count, while rows are still being appended.
          fleet.restartAll();
        }
        UUID tenantId = tenants.get(tx % TENANTS);
        if (rollbackAt.contains(tx)) {
          rolledBack.addAll(appendRolledBack(tenantId, tx));
        } else {
          int first = next;
          appendCommitted(tenantId, tx, first, indexById, proxyPlan, crashBefore, crashAfter);
          next += 10;
        }
        fleet.replaceDead();
      }
      assertThat(indexById).hasSize(EVENTS);

      // Step 4: Rounds. Check Retry-After at each stop, then skip backoffs and dead leases.
      List<String> retryAfterViolations = new ArrayList<>();
      int[] retryAfterChecked = new int[1];
      Rounds rounds =
          drain(
              fleet,
              this::claimableOutbox,
              () -> sentOutbox() == EVENTS,
              () -> checkScheduledRetries(retryAfterViolations, retryAfterChecked));
      long wallMs = Duration.ofNanos(System.nanoTime() - started).toMillis();

      // Step 5: Every committed event is SENT, none DEAD, rolled-back events never existed.
      assertThat(fleet.errors()).isEmpty();
      assertThat(proxy.unexpected()).isEmpty();
      assertThat(outboxStatusCounts()).containsExactly(Map.entry("SENT", (long) EVENTS));
      for (String id : rolledBack) {
        assertThat(count("SELECT count(*) FROM outbox_event WHERE id = ?::uuid", id)).isZero();
      }
      assertThat(
              count(
                  "SELECT count(*) FROM audit_log WHERE action = 'CHAOS_TEST_OUT' AND entity_id LIKE ?",
                  "out-" + SEED + "-%"))
          .isEqualTo(100);

      // Step 6: The receiver verified and stored each committed event exactly once.
      Map<String, Integer> delivered = proxy.delivered();
      Map<String, Integer> accepted = proxy.accepted();
      assertThat(delivered.keySet()).isEqualTo(indexById.keySet());
      assertThat(accepted.keySet()).isEqualTo(indexById.keySet());
      assertThat(accepted.values()).allMatch(count -> count == 1);
      List<String> stored =
          received.all().stream().map(ReceivedEventStore.Received::eventId).toList();
      assertThat(stored).hasSize(EVENTS).doesNotHaveDuplicates();
      assertThat(new HashSet<>(stored)).isEqualTo(indexById.keySet());
      assertThat(stored).noneMatch(rolledBack::contains);
      int totalDeliveries = delivered.values().stream().mapToInt(Integer::intValue).sum();
      assertThat(totalDeliveries - EVENTS).isEqualTo(expectedDuplicates).isPositive();

      // Step 7: Faults fired exactly as planned. Retry-After was honored every time it was sent.
      assertThat(hooks.beforeSendCrashes()).isEqualTo(crashBefore.size());
      assertThat(hooks.afterAckCrashes()).isEqualTo(crashAfter.size());
      assertThat(fleet.deaths()).isEqualTo(crashBefore.size() + crashAfter.size());
      for (Map.Entry<Fault, Integer> entry : expectedFired.entrySet()) {
        assertThat(proxy.fired(entry.getKey()))
            .as(entry.getKey().name())
            .isEqualTo(entry.getValue());
      }
      assertThat(retryAfterViolations).isEmpty();
      assertThat(retryAfterChecked[0]).isEqualTo(20 + 30 + 20);

      // Step 8: No row was held by two publishers. One holder per claim, never two at the proxy.
      assertThat(proxy.overlaps()).isZero();
      Map<String, Set<String>> holders = leaseHolders();
      assertThat(holders).isNotEmpty();
      assertThat(holders.values()).allMatch(instances -> instances.size() == 1);
      assertThat(lostLeaseWarnings()).isZero();

      long attempts = count("SELECT COALESCE(sum(attempts), 0) FROM outbox_event");
      Map<String, Integer> retries = new LinkedHashMap<>();
      retries.put("crash_before_send", hooks.beforeSendCrashes());
      retries.put("crash_after_ack_before_sent", hooks.afterAckCrashes());
      proxy.firedByFault().forEach(retries::put);
      retries.put("backoff_skipped_after_failure", rounds.skippedBackoffs());
      retries.put("orphaned_lease_skipped", rounds.skippedLeases());
      retries.put("claims_beyond_first", (int) (attempts - EVENTS));
      Map<String, Object> section =
          ChaosReport.direction(EVENTS, delivered.size(), totalDeliveries, retries, wallMs);
      section.put("max_attempts", count("SELECT max(attempts) FROM outbox_event"));
      section.put("receiver_requests", proxy.requests());
      section.put("rolled_back_events_never_sent", rolledBack.size());
      section.put("retry_after_checked", retryAfterChecked[0]);
      section.put("tenants", TENANTS);
      section.put("publisher_processes_started", fleet.started());
      section.put("drain_rounds", rounds.count());
      REPORT.put("outbound", section);
    } finally {
      fleet.stopAll();
      proxy.releaseHeld();
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Rounds
  // ---------------------------------------------------------------------------------------------

  private record Rounds(int count, int skippedBackoffs, int skippedLeases) {}

  private interface LongCheck {
    long get() throws Exception;
  }

  private interface Condition {
    boolean done() throws Exception;
  }

  private interface Step {
    void run() throws Exception;
  }

  private Rounds drain(ChaosFleet fleet, LongCheck claimable, Condition finished) throws Exception {
    return drain(fleet, claimable, finished, () -> {});
  }

  /**
   * Waits until nothing is claimable, stops every process, and either finishes or fast-forwards the
   * rows that are waiting on a backoff or on a lease a dead process left behind.
   */
  private Rounds drain(ChaosFleet fleet, LongCheck claimable, Condition finished, Step atQuiescence)
      throws Exception {
    int skippedBackoffs = 0;
    int skippedLeases = 0;
    for (int round = 1; round <= MAX_ROUNDS; round++) {
      await()
          .atMost(ROUND_LIMIT)
          .pollInterval(POLL)
          .until(
              () -> {
                fleet.replaceDead();
                return claimable.get() == 0;
              });
      fleet.stopAll();
      proxy.releaseHeld();
      atQuiescence.run();
      if (finished.done()) {
        return new Rounds(round, skippedBackoffs, skippedLeases);
      }
      int[] skipped = fastForward();
      skippedBackoffs += skipped[0];
      skippedLeases += skipped[1];
      fleet.start();
    }
    throw new AssertionError("delivery did not finish within " + MAX_ROUNDS + " rounds");
  }

  /** Only runs while no process is alive, so no live holder can lose its lease. */
  private static int[] fastForward() throws SQLException {
    try (Connection admin = AuthTestSupport.admin();
        Statement statement = admin.createStatement()) {
      int inboxBackoff =
          statement.executeUpdate(
              """
              UPDATE inbox_event SET next_attempt_at = now()
              WHERE status = 'FAILED' AND next_attempt_at > now()
              """);
      int inboxLease =
          statement.executeUpdate(
              """
              UPDATE inbox_event SET next_attempt_at = now()
              WHERE status = 'RECEIVED' AND next_attempt_at > now()
              """);
      int outboxBackoff =
          statement.executeUpdate(
              """
              UPDATE outbox_event SET next_attempt_at = now() - interval '1 second'
              WHERE status = 'PENDING' AND next_attempt_at > now()
              """);
      int outboxLease =
          statement.executeUpdate(
              """
              UPDATE outbox_event SET lease_until = now() - interval '1 second'
              WHERE status = 'IN_FLIGHT'
              """);
      return new int[] {inboxBackoff + outboxBackoff, inboxLease + outboxLease};
    }
  }

  private long claimableInbox() throws SQLException {
    return count(
        """
        SELECT count(*) FROM inbox_event
        WHERE status IN ('RECEIVED', 'FAILED')
          AND (next_attempt_at IS NULL OR next_attempt_at <= now())
        """);
  }

  private long claimableOutbox() throws SQLException {
    return count(
        """
        SELECT count(*) FROM outbox_event
        WHERE (status = 'PENDING' AND (next_attempt_at IS NULL OR next_attempt_at <= now()))
           OR (status = 'IN_FLIGHT' AND lease_until <= now())
        """);
  }

  private long processedInbox() throws SQLException {
    return count(
        "SELECT count(*) FROM inbox_event WHERE event_type = 'order.created' AND status = 'PROCESSED'");
  }

  private long sentOutbox() throws SQLException {
    return count("SELECT count(*) FROM outbox_event WHERE status = 'SENT'");
  }

  /**
   * A PENDING row was pushed back by its last receiver answer. The delay must be the 4.4 ladder
   * slot for that claim's attempt (jitter 0 here), or Retry-After when that is longer. A row that a
   * crash orphaned earlier is on a later slot.
   */
  private void checkScheduledRetries(List<String> violations, int[] retryAfterChecked)
      throws SQLException {
    try (Connection admin = AuthTestSupport.admin();
        PreparedStatement statement =
            admin.prepareStatement(
                """
                SELECT id::text, next_attempt_at, attempts
                FROM outbox_event WHERE status = 'PENDING'
                """);
        ResultSet rows = statement.executeQuery()) {
      while (rows.next()) {
        String id = rows.getString(1);
        OffsetDateTime nextAttempt = rows.getObject(2, OffsetDateTime.class);
        int attempts = rows.getInt(3);
        ChaosFaultProxy.Answered answer = proxy.lastAnswer(id);
        if (answer == null || nextAttempt == null || attempts < 1 || attempts > LADDER.length) {
          violations.add(id + " is PENDING without a receiver fault (attempts " + attempts + ")");
          continue;
        }
        long seconds = LADDER[attempts - 1].toSeconds();
        if (answer.retryAfterSeconds() != null) {
          seconds = Math.max(seconds, answer.retryAfterSeconds());
        }
        Duration delay = Duration.between(answer.at(), nextAttempt.toInstant());
        if (delay.compareTo(Duration.ofSeconds(seconds - 1)) < 0
            || delay.compareTo(Duration.ofSeconds(seconds + 10)) > 0) {
          violations.add(id + " " + answer.fault() + " retried after " + delay);
        }
        if (answer.retryAfterSeconds() != null) {
          retryAfterChecked[0]++;
        }
      }
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Inbound helpers
  // ---------------------------------------------------------------------------------------------

  private enum UnitKind {
    SEND,
    REPEAT,
    SHUFFLE,
    BAD_SIGNATURE,
    STALE
  }

  /** One control call. A negative index points into the rejected-only events. */
  private record Unit(UnitKind kind, List<Integer> indices, int times) {}

  private List<JsonNode> deliver(Unit unit, List<ObjectNode> events, List<ObjectNode> rejected)
      throws Exception {
    ObjectNode body = JSON.createObjectNode();
    String path;
    switch (unit.kind()) {
      case SEND -> {
        path = "/control/events/send";
        body.set("event", event(unit.indices().get(0), events, rejected));
      }
      case REPEAT -> {
        path = "/control/events/repeat";
        body.put("times", unit.times());
        body.set("event", event(unit.indices().get(0), events, rejected));
      }
      case SHUFFLE -> {
        path = "/control/events/shuffle";
        ArrayNode list = body.putArray("events");
        for (int index : unit.indices()) {
          list.add(event(index, events, rejected));
        }
      }
      case BAD_SIGNATURE -> {
        path = "/control/events/bad-signature";
        body.set("event", event(unit.indices().get(0), events, rejected));
      }
      case STALE -> {
        path = "/control/events/stale";
        body.put("skew_seconds", 301);
        body.set("event", event(unit.indices().get(0), events, rejected));
      }
      default -> throw new IllegalStateException(unit.kind().name());
    }
    JsonNode report = control(path, body);
    List<JsonNode> rows = new ArrayList<>();
    report.path("sent").forEach(rows::add);
    int expected = unit.kind() == UnitKind.REPEAT ? unit.times() : unit.indices().size();
    assertThat(rows).hasSize(expected);
    return rows;
  }

  private static ObjectNode event(int index, List<ObjectNode> events, List<ObjectNode> rejected) {
    return index >= 0 ? events.get(index) : rejected.get(-index - 1);
  }

  private InboxWorker freshInboxWorker() {
    return new InboxWorker(
        inboxProperties, registry, policy, reconciliation, jdbc, transactions, jsonMapper, meters);
  }

  private static final String INBOUND_ID_PATTERN = "chaos-in-" + SEED + "-%";

  private static String inboundId(int index) {
    return "chaos-in-" + SEED + "-" + index;
  }

  private static ObjectNode orderCreated(String eventId, String shopId, int index)
      throws IOException {
    ObjectNode event;
    try (InputStream in =
        MockTsfApplication.class.getResourceAsStream(
            "/contracts/examples/events/order.created.json")) {
      if (in == null) {
        throw new IllegalStateException("order.created example is not on the classpath");
      }
      event = (ObjectNode) JSON.readTree(in);
    }
    String orderId = "TSF-CHAOS-" + SEED + "-" + index;
    event.put("event_id", eventId);
    event.put("tsf_shop_id", shopId);
    event.put("aggregate_id", orderId);
    event.put("aggregate_version", 1);
    event.put("occurred_at", Instant.now().truncatedTo(ChronoUnit.SECONDS).toString());
    ((ObjectNode) event.get("data")).put("order_id", orderId);
    return event;
  }

  private Map<String, Long> inboxStatusCounts() throws SQLException {
    return grouped(
        "SELECT status, count(*) FROM inbox_event WHERE event_type = 'order.created' GROUP BY status");
  }

  private Map<String, Long> effectsByEvent() throws SQLException {
    // Step 1: audit_log is append-only, so scope to this run's event ids instead of deleting.
    return grouped(
        """
        SELECT entity_id, count(*) FROM audit_log
        WHERE action = 'CHAOS_TEST' AND entity_id LIKE ?
        GROUP BY entity_id
        """,
        INBOUND_ID_PATTERN);
  }

  // ---------------------------------------------------------------------------------------------
  // Outbound helpers
  // ---------------------------------------------------------------------------------------------

  private void appendCommitted(
      UUID tenantId,
      int tx,
      int first,
      Map<String, Integer> indexById,
      Map<Integer, List<Fault>> proxyPlan,
      Set<Integer> crashBefore,
      Set<Integer> crashAfter) {
    inTenant(
        tenantId,
        () ->
            new TransactionTemplate(transactions)
                .executeWithoutResult(
                    status -> {
                      for (int index = first; index < first + 10; index++) {
                        UUID id = appender.append(stock(index));
                        // Step 1: Register the plan before commit, so no publisher can beat it.
                        indexById.put(id.toString(), index);
                        List<Fault> faults = proxyPlan.get(index);
                        if (faults != null) {
                          proxy.plan(id.toString(), faults);
                        }
                        if (crashBefore.contains(index)) {
                          hooks.crashBeforeFirstSend(id);
                        }
                        if (crashAfter.contains(index)) {
                          hooks.crashAfterFirstAck(id);
                        }
                      }
                      businessRow(tenantId, tx);
                    }));
  }

  private List<String> appendRolledBack(UUID tenantId, int tx) {
    List<String> ids = new ArrayList<>();
    inTenant(
        tenantId,
        () -> {
          try {
            new TransactionTemplate(transactions)
                .executeWithoutResult(
                    status -> {
                      for (int k = 0; k < 10; k++) {
                        ids.add(appender.append(stock(100_000 + tx * 10 + k)).toString());
                      }
                      businessRow(tenantId, tx);
                      throw new IllegalStateException("chaos: business transaction rolled back");
                    });
          } catch (IllegalStateException expected) {
            // The business change and its events are gone together.
          }
        });
    assertThat(ids).hasSize(10);
    return ids;
  }

  private void businessRow(UUID tenantId, int tx) {
    jdbc.update(
        """
        INSERT INTO audit_log (id, tenant_id, actor_type, action, entity_type, entity_id)
        VALUES (?, ?, 'SYSTEM', 'CHAOS_TEST_OUT', 'chaos_batch', ?)
        """,
        UUID.randomUUID(),
        tenantId,
        "out-" + SEED + "-" + tx);
  }

  private static OutboxDraft stock(int index) {
    Map<String, Object> item = new LinkedHashMap<>();
    item.put("listing_sku_id", "chaos_sku_" + index);
    item.put("seller_sku", "CHAOS-" + index);
    item.put("available", index % 50);
    item.put("stock_version", index + 1);
    return OutboxDraft.of(
        "listing",
        "chaos_sku_" + index,
        "stock.updated",
        Map.of("items", List.of(item)),
        index + 1);
  }

  private Map<String, Long> outboxStatusCounts() throws SQLException {
    return grouped("SELECT status, count(*) FROM outbox_event GROUP BY status");
  }

  private static Map<String, Set<String>> leaseHolders() {
    Map<String, Set<String>> holders = new HashMap<>();
    for (ILoggingEvent event : List.copyOf(LOGS.list)) {
      Matcher matcher = LEASE.matcher(event.getFormattedMessage());
      if (matcher.matches()) {
        String claim = matcher.group(2) + "#" + matcher.group(5);
        holders.computeIfAbsent(claim, ignored -> new HashSet<>()).add(matcher.group(1));
      }
    }
    return holders;
  }

  private static long lostLeaseWarnings() {
    return List.copyOf(LOGS.list).stream()
        .filter(event -> event.getFormattedMessage().startsWith("outbox lost lease"))
        .count();
  }

  private static void attachLogs() {
    // Spring Boot resets Logback while the context starts, so attach after that.
    if (!LOGS.isStarted()) {
      LOGS.start();
    }
    Logger publisherLog = (Logger) LoggerFactory.getLogger(OutboxPublisher.class);
    Logger storeLog = (Logger) LoggerFactory.getLogger(OutboxStore.class);
    publisherLog.setLevel(Level.DEBUG);
    if (!publisherLog.isAttached(LOGS)) {
      publisherLog.addAppender(LOGS);
    }
    if (!storeLog.isAttached(LOGS)) {
      storeLog.addAppender(LOGS);
    }
    LOGS.list.clear();
  }

  // ---------------------------------------------------------------------------------------------
  // Shared helpers
  // ---------------------------------------------------------------------------------------------

  /** Registers shops through membership.changed and lets the inbox worker apply them. */
  private List<String> provisionShops(String prefix, int count) throws Exception {
    List<String> shops = new ArrayList<>();
    for (int k = 0; k < count; k++) {
      String shop = prefix + "-" + k;
      ObjectNode body = JSON.createObjectNode();
      body.set("event", membership("chaos-membership-" + shop, shop));
      JsonNode report = control("/control/events/send", body);
      assertThat(report.path("sent").get(0).path("http_status").asInt()).isEqualTo(202);
      shops.add(shop);
    }
    while (worker.processAvailable(20) > 0) {
      // Drain membership rows. Nothing else is queued yet.
    }
    assertThat(
            count(
                """
                SELECT count(*) FROM inbox_event
                WHERE event_type = 'membership.changed' AND status = 'PROCESSED'
                  AND event_id LIKE ?
                """,
                "chaos-membership-" + prefix + "%"))
        .isEqualTo(count);
    return shops;
  }

  private static ObjectNode membership(String eventId, String shopId) {
    ObjectNode data = JSON.createObjectNode();
    data.put("name", "Chaos " + shopId);
    data.put("tier", "PRO");
    data.put("status", "ACTIVE");
    data.put("ent_ver", 1);
    data.put("expires_at", Instant.now().plus(365, ChronoUnit.DAYS).toString());
    ObjectNode event = JSON.createObjectNode();
    event.put("event_id", eventId);
    event.put("event_type", "membership.changed");
    event.put("schema_version", 1);
    event.put("occurred_at", Instant.now().truncatedTo(ChronoUnit.SECONDS).toString());
    event.put("tsf_shop_id", shopId);
    event.put("aggregate_id", shopId);
    event.put("aggregate_version", 1);
    event.set("data", data);
    return event;
  }

  private static UUID tenantId(String shopId) throws SQLException {
    try (Connection admin = AuthTestSupport.admin();
        PreparedStatement statement =
            admin.prepareStatement("SELECT id FROM tenant WHERE tsf_shop_id = ?")) {
      statement.setString(1, shopId);
      try (ResultSet rows = statement.executeQuery()) {
        assertThat(rows.next()).isTrue();
        return rows.getObject(1, UUID.class);
      }
    }
  }

  private static void inTenant(UUID tenantId, Runnable work) {
    TenantContext.set(tenantId, null);
    try {
      work.run();
    } finally {
      TenantContext.clear();
    }
  }

  private static List<Integer> permutation(int size, Random random) {
    List<Integer> order = new ArrayList<>();
    for (int i = 0; i < size; i++) {
      order.add(i);
    }
    Collections.shuffle(order, random);
    return order;
  }

  private static JsonNode control(String path, ObjectNode body) throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(mockUri(path))
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
            .build();
    HttpResponse<String> response =
        HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    return JSON.readTree(response.body());
  }

  private static long count(String sql, Object... args) throws SQLException {
    try (Connection admin = AuthTestSupport.admin();
        PreparedStatement statement = admin.prepareStatement(sql)) {
      for (int i = 0; i < args.length; i++) {
        statement.setObject(i + 1, args[i]);
      }
      try (ResultSet rows = statement.executeQuery()) {
        rows.next();
        return rows.getLong(1);
      }
    }
  }

  private static Map<String, Long> grouped(String sql, Object... args) throws SQLException {
    Map<String, Long> out = new LinkedHashMap<>();
    try (Connection admin = AuthTestSupport.admin();
        PreparedStatement statement = admin.prepareStatement(sql)) {
      for (int i = 0; i < args.length; i++) {
        statement.setObject(i + 1, args[i]);
      }
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          out.put(rows.getString(1), rows.getLong(2));
        }
      }
    }
    return out;
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
    String value = mock.getEnvironment().getProperty("local.server.port");
    if (value == null || value.isBlank() || "0".equals(value)) {
      throw new IllegalStateException("mock-tsf did not bind a port");
    }
    return Integer.parseInt(value);
  }

  private static URI mockUri(String path) {
    return URI.create("http://127.0.0.1:" + mockPort() + path);
  }

  /** Test-only beans. Imported by this class alone; never picked up by component scan. */
  @TestConfiguration
  static class ChaosConfig {

    @Bean
    ChaosOrderCreatedHandler chaosOrderCreatedHandler(JdbcTemplate jdbc) {
      return new ChaosOrderCreatedHandler(jdbc);
    }

    @Bean
    @Primary
    ChaosOutboxHooks chaosOutboxHooks() {
      return new ChaosOutboxHooks();
    }
  }
}
