package com.thaishopfun.oms.inbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.thaishopfun.oms.auth.AuthTestSupport;
import com.thaishopfun.oms.auth.UuidV7;
import com.thaishopfun.oms.tenant.TenantContext;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(InboxApiTest.Handlers.class)
class InboxApiTest {

  private static final String CURRENT = "test-inbox-secret";
  private static final String PREVIOUS = "test-inbox-secret-old";
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final HttpClient HTTP =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    AuthTestSupport.register(registry);
    registry.add("oms.inbox.hmac-secrets", () -> CURRENT + "," + PREVIOUS);
    registry.add("oms.inbox.jitter-ratio", () -> "0");
    registry.add("oms.inbox.suspend-defer", () -> "1h");
    registry.add("spring.datasource.hikari.maximum-pool-size", () -> "20");
  }

  @LocalServerPort private int port;

  @Autowired private InboxWorker worker;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private PlatformTransactionManager transactions;
  @Autowired private MeterRegistry meters;

  @Autowired
  @Qualifier("orderCreated")
  private EffectHandler orderCreated;

  @Autowired
  @Qualifier("orderFail")
  private EffectHandler orderFail;

  private String serviceToken;

  @BeforeEach
  void reset() throws Exception {
    serviceToken =
        AuthTestSupport.token(
            "tsf-checkout",
            "unused",
            "ACTIVE",
            Instant.now().plus(1, ChronoUnit.DAYS),
            1,
            "oms-internal",
            Instant.now().plusSeconds(600),
            List.of("oms"));
    orderCreated.reset();
    orderFail.reset();
    try (Connection admin = AuthTestSupport.admin();
        var statement = admin.createStatement()) {
      statement.execute("TRUNCATE TABLE inbox_event");
    }
  }

  @Test
  void badOrStaleSignatureIs401() throws Exception {
    Shop shop = seed("ACTIVE", future(), 1);
    byte[] body = envelope(id(), "order.created", shop.shopId(), "agg", 1, Map.of());

    HttpResult ok = post(body, eventId(body), sign(CURRENT, now(), body), serviceToken);
    assertThat(ok.status()).isEqualTo(202);

    byte[] bad = envelope(id(), "order.created", shop.shopId(), "agg", 1, Map.of());
    assertThat(post(bad, eventId(bad), sign("nope", now(), bad), serviceToken).status())
        .isEqualTo(401);
    assertThat(count("SELECT count(*) FROM inbox_event WHERE event_id = ?", eventId(bad))).isZero();

    byte[] stale = envelope(id(), "order.created", shop.shopId(), "agg", 1, Map.of());
    String staleAt = Long.toString(Instant.now().getEpochSecond() - 301);
    HttpResult staleResult =
        post(stale, eventId(stale), sign(CURRENT, staleAt, stale), serviceToken);
    assertThat(staleResult.status()).isEqualTo(401);
    assertError(staleResult, "UNAUTHORIZED");

    byte[] futureBody = envelope(id(), "order.created", shop.shopId(), "agg", 1, Map.of());
    String futureAt = Long.toString(Instant.now().getEpochSecond() + 301);
    assertThat(
            post(futureBody, eventId(futureBody), sign(CURRENT, futureAt, futureBody), serviceToken)
                .status())
        .isEqualTo(401);

    byte[] rotated = envelope(id(), "order.created", shop.shopId(), "agg", 1, Map.of());
    assertThat(
            post(rotated, eventId(rotated), sign(PREVIOUS, now(), rotated), serviceToken).status())
        .isEqualTo(202);

    assertThat(post(body, eventId(body), null, serviceToken).status()).isEqualTo(401);
    String userToken =
        AuthTestSupport.userToken(
            "user-" + UUID.randomUUID(), shop.shopId(), "ACTIVE", future(), 1);
    assertThat(post(body, eventId(body), sign(CURRENT, now(), body), userToken).status())
        .isEqualTo(401);
    assertThat(post(body, eventId(body), sign(CURRENT, now(), body), null).status()).isEqualTo(401);
  }

  @Test
  void invalidBodyIs400AndUnknownShopIs503() throws Exception {
    Shop shop = seed("ACTIVE", future(), 1);
    byte[] body = envelope(id(), "order.created", shop.shopId(), "agg", 1, Map.of());
    HttpResult mismatch = post(body, "other-event", sign(CURRENT, now(), body), serviceToken);
    assertThat(mismatch.status()).isEqualTo(400);
    assertError(mismatch, "BAD_REQUEST");
    assertThat(count("SELECT count(*) FROM inbox_event WHERE event_id = ?", eventId(body)))
        .isZero();

    byte[] broken = "{".getBytes(StandardCharsets.UTF_8);
    assertThat(post(broken, "evt", sign(CURRENT, now(), broken), serviceToken).status())
        .isEqualTo(400);

    byte[] unknown = envelope(id(), "order.created", "missing-shop", "agg", 1, Map.of());
    HttpResult missing =
        post(unknown, eventId(unknown), sign(CURRENT, now(), unknown), serviceToken);
    assertThat(missing.status()).isEqualTo(503);
    assertThat(missing.retryAfter()).isEqualTo("60");
    assertError(missing, "TENANT_NOT_READY");
    assertThat(count("SELECT count(*) FROM inbox_event WHERE event_id = ?", eventId(unknown)))
        .isZero();

    String huge =
        new String(
            envelope(id(), "order.created", "missing-shop", "agg", 1, Map.of()),
            StandardCharsets.UTF_8);
    huge = huge.replace("\"aggregate_version\":1", "\"aggregate_version\":18446744073709551617");
    byte[] overflow = huge.getBytes(StandardCharsets.UTF_8);
    assertThat(
            post(overflow, eventId(overflow), sign(CURRENT, now(), overflow), serviceToken)
                .status())
        .isEqualTo(400);

    byte[] nul =
        new String(
                envelope(id(), "order.created", shop.shopId(), "agg", 1, Map.of()),
                StandardCharsets.UTF_8)
            .replace("\"data\":{}", "\"data\":{\"note\":\"bad\\u0000\"}")
            .getBytes(StandardCharsets.UTF_8);
    HttpResult nulResult = post(nul, eventId(nul), sign(CURRENT, now(), nul), serviceToken);
    assertThat(nulResult.status()).isEqualTo(400);
    assertError(nulResult, "BAD_REQUEST");
    assertThat(count("SELECT count(*) FROM inbox_event WHERE event_id = ?", eventId(nul))).isZero();

    byte[] oversized = new byte[InboxIngestService.MAX_BODY_BYTES + 1];
    java.util.Arrays.fill(oversized, (byte) 'x');
    HttpResult tooBig = post(oversized, "evt-big", sign(CURRENT, now(), oversized), serviceToken);
    assertThat(tooBig.status()).isEqualTo(413);
    assertError(tooBig, "PAYLOAD_TOO_LARGE");
  }

  @Test
  void duplicateDeliveryRunsHandlerOnce() throws Exception {
    Shop shop = seed("ACTIVE", future(), 1);
    String eventId = id();
    byte[] body = envelope(eventId, "order.created", shop.shopId(), "agg-dup", 1, Map.of("n", 1));
    String signature = sign(CURRENT, now(), body);

    assertThat(post(body, eventId, signature, serviceToken).status()).isEqualTo(202);
    String receivedAt =
        text("SELECT received_at::text FROM inbox_event WHERE event_id = ?", eventId);
    for (int i = 0; i < 4; i++) {
      assertThat(post(body, eventId, signature, serviceToken).status()).isEqualTo(200);
    }
    assertThat(text("SELECT received_at::text FROM inbox_event WHERE event_id = ?", eventId))
        .isEqualTo(receivedAt);
    assertThat(count("SELECT count(*) FROM inbox_event WHERE event_id = ?", eventId)).isEqualTo(1);

    byte[] changed =
        envelope(eventId, "order.created", shop.shopId(), "agg-dup", 9, Map.of("n", 2));
    double beforeMismatch = meters.counter(InboxIngestService.MISMATCH_METRIC).count();
    assertThat(post(changed, eventId, sign(CURRENT, now(), changed), serviceToken).status())
        .isEqualTo(200);
    assertThat(meters.counter(InboxIngestService.MISMATCH_METRIC).count())
        .isGreaterThan(beforeMismatch);
    assertThat(count("SELECT aggregate_version FROM inbox_event WHERE event_id = ?", eventId))
        .isEqualTo(1);
    assertThat(text("SELECT payload::text FROM inbox_event WHERE event_id = ?", eventId))
        .contains("\"n\": 1")
        .doesNotContain("\"n\": 2");

    assertThat(worker.processAvailable()).isEqualTo(1);
    assertThat(orderCreated.calls.get()).isEqualTo(1);
    assertThat(count("SELECT count(*) FROM audit_log WHERE entity_id = ?", eventId)).isEqualTo(1);
    assertThat(text("SELECT status FROM inbox_event WHERE event_id = ?", eventId))
        .isEqualTo("PROCESSED");

    assertThat(post(body, eventId, signature, serviceToken).status()).isEqualTo(200);
    assertThat(worker.processAvailable()).isZero();
    assertThat(orderCreated.calls.get()).isEqualTo(1);
  }

  @Test
  void concurrentPostsDedupToOneRow() throws Exception {
    Shop shop = seed("ACTIVE", future(), 1);
    String eventId = id();
    byte[] body = envelope(eventId, "order.created", shop.shopId(), "agg-race", 1, Map.of());
    String signature = sign(CURRENT, now(), body);
    int threads = 8;
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    CountDownLatch start = new CountDownLatch(1);
    List<Future<Integer>> futures = new ArrayList<>();
    for (int i = 0; i < threads; i++) {
      futures.add(
          pool.submit(
              () -> {
                start.await(5, TimeUnit.SECONDS);
                return post(body, eventId, signature, serviceToken).status();
              }));
    }
    start.countDown();
    List<Integer> statuses = new ArrayList<>();
    for (Future<Integer> future : futures) {
      statuses.add(future.get(10, TimeUnit.SECONDS));
    }
    pool.shutdownNow();
    assertThat(statuses.stream().filter(status -> status == 202).count()).isEqualTo(1);
    assertThat(statuses.stream().filter(status -> status == 200).count()).isEqualTo(threads - 1);
    assertThat(count("SELECT count(*) FROM inbox_event WHERE event_id = ?", eventId)).isEqualTo(1);

    worker.processAvailable();
    assertThat(orderCreated.calls.get()).isEqualTo(1);
    assertThat(count("SELECT count(*) FROM audit_log WHERE entity_id = ?", eventId)).isEqualTo(1);
  }

  @Test
  void concurrentWorkersDoNotDoubleProcess() throws Exception {
    Shop shop = seed("ACTIVE", future(), 1);
    String eventId = id();
    byte[] body = envelope(eventId, "order.created", shop.shopId(), "agg-one", 1, Map.of());
    assertThat(post(body, eventId, sign(CURRENT, now(), body), serviceToken).status())
        .isEqualTo(202);

    ExecutorService pool = Executors.newFixedThreadPool(2);
    CountDownLatch start = new CountDownLatch(1);
    Future<Integer> left = pool.submit(() -> awaitThenProcess(start));
    Future<Integer> right = pool.submit(() -> awaitThenProcess(start));
    start.countDown();
    left.get(10, TimeUnit.SECONDS);
    right.get(10, TimeUnit.SECONDS);
    pool.shutdownNow();

    assertThat(orderCreated.calls.get()).isEqualTo(1);
    assertThat(text("SELECT status FROM inbox_event WHERE event_id = ?", eventId))
        .isEqualTo("PROCESSED");
  }

  @Test
  void handlerFailureRollsBackThenRetriesToDead() throws Exception {
    Shop shop = seed("ACTIVE", future(), 1);
    String eventId = id();
    byte[] body =
        envelope(
            eventId, "order.fail", shop.shopId(), "agg-fail", 1, Map.of("phone", "0812341234"));
    assertThat(post(body, eventId, sign(CURRENT, now(), body), serviceToken).status())
        .isEqualTo(202);

    long[] waits = {30, 120, 600, 1800, 3600, 10800, 21600};
    for (int attempt = 0; attempt < waits.length; attempt++) {
      assertThat(worker.processAvailable()).isEqualTo(1);
      InboxState row = state(eventId);
      assertThat(row.status()).isEqualTo("FAILED");
      assertThat(row.attempts()).isEqualTo(attempt + 1);
      assertThat(row.waitSeconds()).isBetween(waits[attempt] - 3d, waits[attempt] + 3d);
      assertThat(row.lastError()).isEqualTo("handler failed").doesNotContain("0812341234");
      assertThat(tenantName(shop.id())).isEqualTo("Shop");
      assertThat(count("SELECT count(*) FROM audit_log WHERE entity_id = ?", eventId)).isZero();
      rewind(eventId);
    }

    assertThat(worker.processAvailable()).isEqualTo(1);
    InboxState dead = state(eventId);
    assertThat(dead.status()).isEqualTo("DEAD");
    assertThat(dead.attempts()).isEqualTo(8);
    assertThat(dead.waitSeconds()).isNull();
    assertThat(dead.lastError()).doesNotContain("0812341234");
    assertThat(orderFail.calls.get()).isEqualTo(8);
    assertThat(tenantName(shop.id())).isEqualTo("Shop");
    assertThat(meters.get(InboxWorker.DEAD_METRIC).counter().count()).isPositive();
  }

  @Test
  void sameAggregateIsProcessedSerially() throws Exception {
    Shop shop = seed("ACTIVE", future(), 1);
    String first = id();
    String second = id();
    postOrder(shop, first, "same-agg", 1);
    postOrder(shop, second, "same-agg", 2);
    orderCreated.block = true;
    orderCreated.entered = new CountDownLatch(1);
    orderCreated.release = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      CountDownLatch start = new CountDownLatch(1);
      Future<Integer> left = pool.submit(() -> awaitThenProcessOne(start));
      Future<Integer> right = pool.submit(() -> awaitThenProcessOne(start));
      start.countDown();
      assertThat(orderCreated.entered.await(10, TimeUnit.SECONDS)).isTrue();
      assertThat(awaitAdvisoryWaiter()).isTrue();
      assertThat(orderCreated.calls.get()).isEqualTo(1);
      orderCreated.release.countDown();
      left.get(10, TimeUnit.SECONDS);
      right.get(10, TimeUnit.SECONDS);
    } finally {
      orderCreated.release.countDown();
      pool.shutdownNow();
    }
    assertThat(orderCreated.calls.get()).isEqualTo(2);
    assertThat(orderCreated.maxInFlight.get()).isEqualTo(1);
    assertThat(text("SELECT status FROM inbox_event WHERE event_id = ?", first))
        .isEqualTo("PROCESSED");
    assertThat(text("SELECT status FROM inbox_event WHERE event_id = ?", second))
        .isEqualTo("PROCESSED");
  }

  @Test
  void differentAggregatesCanRunTogether() throws Exception {
    Shop shop = seed("ACTIVE", future(), 1);
    postOrder(shop, id(), "agg-a", 1);
    postOrder(shop, id(), "agg-b", 1);
    orderCreated.block = true;
    orderCreated.entered = new CountDownLatch(2);
    orderCreated.release = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      CountDownLatch start = new CountDownLatch(1);
      Future<Integer> left = pool.submit(() -> awaitThenProcessOne(start));
      Future<Integer> right = pool.submit(() -> awaitThenProcessOne(start));
      start.countDown();
      assertThat(orderCreated.entered.await(10, TimeUnit.SECONDS)).isTrue();
      assertThat(orderCreated.maxInFlight.get()).isEqualTo(2);
      orderCreated.release.countDown();
      left.get(10, TimeUnit.SECONDS);
      right.get(10, TimeUnit.SECONDS);
    } finally {
      orderCreated.release.countDown();
      pool.shutdownNow();
    }
    assertThat(orderCreated.calls.get()).isEqualTo(2);
  }

  @Test
  void suspendedShopDefersOrdersUntilMembershipChanges() throws Exception {
    Shop shop = seed("SUSPENDED", null, 1);
    String orderId = id();
    postOrder(shop, orderId, "order-" + orderId, 1);
    assertThat(worker.processAvailable()).isEqualTo(1);
    InboxState deferred = state(orderId);
    assertThat(deferred.status()).isEqualTo("RECEIVED");
    assertThat(deferred.attempts()).isZero();
    assertThat(deferred.lastError()).isEqualTo(InboxWorker.ENTITLEMENT_DEFERRED);
    assertThat(deferred.waitSeconds()).isBetween(3500d, 3700d);
    assertThat(orderCreated.calls.get()).isZero();

    String membershipId = id();
    postMembership(shop, membershipId, "ACTIVE", 4, future(), 1);
    assertThat(worker.processAvailable()).isEqualTo(1);
    assertThat(text("SELECT status FROM inbox_event WHERE event_id = ?", membershipId))
        .isEqualTo("PROCESSED");
    assertThat(
            text("SELECT entitlement_status FROM tenant WHERE id = ?::uuid", shop.id().toString()))
        .isEqualTo("ACTIVE");
    assertThat(count("SELECT ent_ver FROM tenant WHERE id = ?::uuid", shop.id().toString()))
        .isEqualTo(4);
    assertThat(text("SELECT status FROM inbox_event WHERE event_id = ?", orderId))
        .isEqualTo("RECEIVED");
    assertThat(orderCreated.calls.get()).isZero();
    assertThat(state(orderId).waitSeconds()).isLessThan(5);

    assertThat(worker.processAvailable()).isEqualTo(1);
    assertThat(text("SELECT status FROM inbox_event WHERE event_id = ?", orderId))
        .isEqualTo("PROCESSED");
    assertThat(orderCreated.calls.get()).isEqualTo(1);

    String staleId = id();
    postMembership(shop, staleId, "SUSPENDED", 3, null, 2);
    assertThat(worker.processAvailable()).isEqualTo(1);
    assertThat(text("SELECT status FROM inbox_event WHERE event_id = ?", staleId))
        .isEqualTo("PROCESSED");
    assertThat(
            text("SELECT entitlement_status FROM tenant WHERE id = ?::uuid", shop.id().toString()))
        .isEqualTo("ACTIVE");
    assertThat(count("SELECT ent_ver FROM tenant WHERE id = ?::uuid", shop.id().toString()))
        .isEqualTo(4);
  }

  @Test
  void activeMembershipLeavesANormalFailureBackoffAlone() throws Exception {
    Shop shop = seed("ACTIVE", future(), 1);
    String eventId = id();
    byte[] body = envelope(eventId, "order.fail", shop.shopId(), "agg-fail", 1, Map.of());
    assertThat(post(body, eventId, sign(CURRENT, now(), body), serviceToken).status())
        .isEqualTo(202);
    assertThat(worker.processAvailable()).isEqualTo(1);
    InboxState failed = state(eventId);
    assertThat(failed.status()).isEqualTo("FAILED");
    assertThat(failed.attempts()).isEqualTo(1);
    assertThat(failed.lastError()).isEqualTo("handler failed");
    OffsetDateTime due = nextAttemptAt(eventId);

    String membershipId = id();
    postMembership(shop, membershipId, "ACTIVE", 2, future(), 1);
    assertThat(worker.processAvailable()).isEqualTo(1);
    assertThat(text("SELECT status FROM inbox_event WHERE event_id = ?", membershipId))
        .isEqualTo("PROCESSED");
    InboxState after = state(eventId);
    assertThat(after.status()).isEqualTo("FAILED");
    assertThat(after.attempts()).isEqualTo(1);
    assertThat(after.lastError()).isEqualTo("handler failed");
    assertThat(nextAttemptAt(eventId).toInstant()).isEqualTo(due.toInstant());
    assertThat(orderFail.calls.get()).isEqualTo(1);
  }

  @Test
  void newerMembershipAppliesWhenAggregateVersionLooksStale() throws Exception {
    Shop shop = seed("SUSPENDED", null, 1);
    String first = id();
    postMembership(shop, first, "SUSPENDED", 2, null, 10);
    assertThat(worker.processAvailable()).isEqualTo(1);
    assertThat(count("SELECT ent_ver FROM tenant WHERE id = ?::uuid", shop.id().toString()))
        .isEqualTo(2);

    String second = id();
    postMembership(shop, second, "ACTIVE", 3, future(), 1);
    assertThat(worker.processAvailable()).isEqualTo(1);
    assertThat(text("SELECT status FROM inbox_event WHERE event_id = ?", second))
        .isEqualTo("PROCESSED");
    assertThat(
            text("SELECT entitlement_status FROM tenant WHERE id = ?::uuid", shop.id().toString()))
        .isEqualTo("ACTIVE");
    assertThat(count("SELECT ent_ver FROM tenant WHERE id = ?::uuid", shop.id().toString()))
        .isEqualTo(3);
  }

  @Test
  void dueRetriesAreClaimedBeforeNewerEvents() throws Exception {
    Shop shop = seed("ACTIVE", future(), 1);
    UUID olderRetry =
        insertInbox(shop.id(), "retry-old", "FAILED", 1, 7200, 10800, "handler failed");
    UUID freshWaiting = insertInbox(shop.id(), "fresh-mid", "RECEIVED", 0, null, 5400, null);
    UUID newerRetry =
        insertInbox(shop.id(), "retry-new", "FAILED", 1, 1800, 4000, "handler failed");
    insertInbox(shop.id(), "fresh-new", "RECEIVED", 0, null, 0, null);

    assertThat(claimOne()).containsExactly(olderRetry);
    assertThat(claimOne()).containsExactly(freshWaiting);
    assertThat(claimOne()).containsExactly(newerRetry);
    assertThat(state("fresh-new").status()).isEqualTo("RECEIVED");
    assertThat(state("fresh-new").attempts()).isZero();
    assertThat(state("retry-old").attempts()).isEqualTo(2);
    assertThat(state("fresh-mid").attempts()).isEqualTo(1);
    assertThat(state("retry-new").attempts()).isEqualTo(2);
  }

  @Test
  void graceShopProcessesOrdersAndExpiredShopsDefer() throws Exception {
    Shop grace = seed("GRACE", future(), 1);
    String graceOrder = id();
    postOrder(grace, graceOrder, "grace-agg", 1);
    worker.processAvailable();
    assertThat(text("SELECT status FROM inbox_event WHERE event_id = ?", graceOrder))
        .isEqualTo("PROCESSED");
    assertThat(orderCreated.calls.get()).isEqualTo(1);

    Shop expired = seed("ACTIVE", Instant.now().minus(1, ChronoUnit.DAYS), 1);
    String expiredOrder = id();
    postOrder(expired, expiredOrder, "expired-agg", 1);
    orderCreated.reset();
    worker.processAvailable();
    assertThat(state(expiredOrder).status()).isEqualTo("RECEIVED");
    assertThat(state(expiredOrder).attempts()).isZero();
    assertThat(orderCreated.calls.get()).isZero();

    Shop expiredGrace = seed("GRACE", Instant.now().minus(1, ChronoUnit.HOURS), 1);
    String graceExpiredOrder = id();
    postOrder(expiredGrace, graceExpiredOrder, "grace-expired", 1);
    worker.processAvailable();
    assertThat(state(graceExpiredOrder).status()).isEqualTo("RECEIVED");
    assertThat(orderCreated.calls.get()).isZero();
  }

  @Test
  void staleAggregateVersionSkipsHandlerAndGapStillRuns() throws Exception {
    Shop shop = seed("ACTIVE", future(), 1);
    String current = id();
    String same = id();
    String older = id();
    postOrder(shop, current, "versioned", 2);
    postOrder(shop, same, "versioned", 2);
    postOrder(shop, older, "versioned", 1);
    stampReceived(current, 3);
    stampReceived(same, 2);
    stampReceived(older, 1);
    worker.processAvailable();
    assertThat(orderCreated.calls.get()).isEqualTo(1);
    assertThat(text("SELECT status FROM inbox_event WHERE event_id = ?", same))
        .isEqualTo("PROCESSED");
    assertThat(text("SELECT status FROM inbox_event WHERE event_id = ?", older))
        .isEqualTo("PROCESSED");

    orderCreated.reset();
    String base = id();
    String skipped = id();
    postOrder(shop, base, "gapped", 1);
    postOrder(shop, skipped, "gapped", 3);
    stampReceived(base, 2);
    stampReceived(skipped, 1);
    worker.processAvailable();
    assertThat(orderCreated.calls.get()).isEqualTo(2);
    assertThat(orderCreated.gaps).containsExactly(false, true);
  }

  @Test
  void missingAggregateVersionIs400AndMembershipMayOmitIt() throws Exception {
    Shop shop = seed("ACTIVE", future(), 1);
    byte[] missing =
        envelopeWithoutVersion(id(), "order.created", shop.shopId(), "unversioned", Map.of());
    HttpResult rejected =
        post(missing, eventId(missing), sign(CURRENT, now(), missing), serviceToken);
    assertThat(rejected.status()).isEqualTo(400);
    assertError(rejected, "BAD_REQUEST");
    assertThat(count("SELECT count(*) FROM inbox_event WHERE event_id = ?", eventId(missing)))
        .isZero();

    String eventId = id();
    byte[] membership =
        envelopeWithoutVersion(
            eventId,
            "membership.changed",
            shop.shopId(),
            shop.shopId(),
            membershipData("ACTIVE", 2, future()));
    assertThat(post(membership, eventId, sign(CURRENT, now(), membership), serviceToken).status())
        .isEqualTo(202);
    worker.processAvailable();
    assertThat(text("SELECT status FROM inbox_event WHERE event_id = ?", eventId))
        .isEqualTo("PROCESSED");
    assertThat(count("SELECT ent_ver FROM tenant WHERE id = ?::uuid", shop.id().toString()))
        .isEqualTo(2);
  }

  @Test
  void unknownEventStaysReceivedUntilAHandlerExists() throws Exception {
    Shop shop = seed("ACTIVE", future(), 1);
    String eventId = id();
    byte[] body = envelope(eventId, "test.unknown", shop.shopId(), "agg", 1, Map.of());
    assertThat(post(body, eventId, sign(CURRENT, now(), body), serviceToken).status())
        .isEqualTo(202);
    double before = meters.counter(InboxWorker.UNKNOWN_METRIC).count();
    assertThat(worker.processAvailable()).isEqualTo(1);
    InboxState row = state(eventId);
    assertThat(row.status()).isEqualTo("RECEIVED");
    assertThat(row.attempts()).isZero();
    assertThat(row.waitSeconds()).isBetween(3500d, 3700d);
    assertThat(meters.counter(InboxWorker.UNKNOWN_METRIC).count()).isGreaterThan(before);
    assertThat(orderCreated.calls.get()).isZero();
    assertThat(worker.processAvailable()).isZero();
  }

  @Test
  void sameEventIdIsIsolatedPerTenant() throws Exception {
    Shop left = seed("ACTIVE", future(), 1);
    Shop right = seed("ACTIVE", future(), 1);
    String eventId = id();
    byte[] leftBody =
        envelope(eventId, "order.created", left.shopId(), "agg", 1, Map.of("side", "L"));
    byte[] rightBody =
        envelope(eventId, "order.created", right.shopId(), "agg", 1, Map.of("side", "R"));
    assertThat(post(leftBody, eventId, sign(CURRENT, now(), leftBody), serviceToken).status())
        .isEqualTo(202);
    assertThat(post(rightBody, eventId, sign(CURRENT, now(), rightBody), serviceToken).status())
        .isEqualTo(202);
    assertThat(count("SELECT count(*) FROM inbox_event WHERE event_id = ?", eventId)).isEqualTo(2);
    assertThat(
            text(
                "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = ?",
                "inbox_event_tenant_source_event_key"))
        .contains("tenant_id")
        .contains("source")
        .contains("event_id");
    assertThat(
            count(
                "SELECT count(*) FROM pg_constraint WHERE conname = ?",
                "inbox_event_source_event_key"))
        .isZero();
    assertThat(
            count(
                "SELECT count(*) FROM pg_class WHERE relname = ?",
                "inbox_event_aggregate_processed_idx"))
        .isEqualTo(1);
    assertThat(text("SELECT relforcerowsecurity::text FROM pg_class WHERE relname = 'inbox_event'"))
        .isEqualTo("true");

    TransactionTemplate tx = new TransactionTemplate(transactions);
    TenantContext.set(left.id(), null);
    try {
      Long visible =
          tx.execute(status -> jdbc.queryForObject("SELECT count(*) FROM inbox_event", Long.class));
      Integer leaked =
          tx.execute(
              status ->
                  jdbc.update(
                      "UPDATE inbox_event SET last_error = 'leak' WHERE tenant_id = ?",
                      right.id()));
      assertThat(visible).isEqualTo(1);
      assertThat(leaked).isZero();
    } finally {
      TenantContext.clear();
    }
    TenantContext.set(right.id(), null);
    try {
      Long visible =
          tx.execute(status -> jdbc.queryForObject("SELECT count(*) FROM inbox_event", Long.class));
      assertThat(visible).isEqualTo(1);
    } finally {
      TenantContext.clear();
    }
    Long hidden =
        tx.execute(status -> jdbc.queryForObject("SELECT count(*) FROM inbox_event", Long.class));
    assertThat(hidden).isZero();

    worker.processAvailable();
    assertThat(orderCreated.calls.get()).isEqualTo(2);
    assertThat(orderCreated.tenantsSeen).containsExactlyInAnyOrder(left.id(), right.id());
  }

  @Test
  void ackTimerRecordsAccepted() throws Exception {
    Shop shop = seed("ACTIVE", future(), 1);
    for (int i = 0; i < 3; i++) {
      String eventId = id();
      byte[] body = envelope(eventId, "order.created", shop.shopId(), "lat", 1, Map.of());
      assertThat(post(body, eventId, sign(CURRENT, now(), body), serviceToken).status())
          .isEqualTo(202);
    }
    Timer timer = meters.find(InboxController.ACK_METRIC).tag("result", "accepted").timer();
    assertThat(timer).isNotNull();
    assertThat(timer.count()).isGreaterThanOrEqualTo(3);
  }

  @Test
  void expiredLeaseCanBeTakenOverAndStaleClaimantDoesNothing() throws Exception {
    Shop shop = seed("ACTIVE", future(), 1);
    String eventId = id();
    postOrder(shop, eventId, "lease-agg", 1);
    UUID rowId =
        UUID.fromString(text("SELECT id::text FROM inbox_event WHERE event_id = ?", eventId));
    OffsetDateTime held =
        OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(10).truncatedTo(ChronoUnit.MILLIS);
    try (Connection admin = AuthTestSupport.admin();
        PreparedStatement statement =
            admin.prepareStatement(
                "UPDATE inbox_event SET attempts = 1, next_attempt_at = ? WHERE event_id = ?")) {
      statement.setObject(1, held);
      statement.setString(2, eventId);
      assertThat(statement.executeUpdate()).isEqualTo(1);
    }

    worker.applyClaim(rowId, shop.id(), held.minusHours(1));
    assertThat(text("SELECT status FROM inbox_event WHERE event_id = ?", eventId))
        .isEqualTo("RECEIVED");
    assertThat(orderCreated.calls.get()).isZero();

    rewind(eventId);
    assertThat(worker.processAvailable()).isEqualTo(1);
    assertThat(text("SELECT status FROM inbox_event WHERE event_id = ?", eventId))
        .isEqualTo("PROCESSED");
    assertThat(orderCreated.calls.get()).isEqualTo(1);

    worker.applyClaim(rowId, shop.id(), held);
    assertThat(orderCreated.calls.get()).isEqualTo(1);
  }

  @Test
  void attemptsPastTheLadderGoDeadWithoutAnotherHandlerCall() throws Exception {
    Shop shop = seed("ACTIVE", future(), 1);
    String eventId = id();
    postOrder(shop, eventId, "max-agg", 1);
    try (Connection admin = AuthTestSupport.admin();
        PreparedStatement statement =
            admin.prepareStatement(
                "UPDATE inbox_event SET attempts = 8, next_attempt_at = now() - interval '1 second' "
                    + "WHERE event_id = ?")) {
      statement.setString(1, eventId);
      assertThat(statement.executeUpdate()).isEqualTo(1);
    }
    assertThat(worker.processAvailable()).isEqualTo(1);
    InboxState dead = state(eventId);
    assertThat(dead.status()).isEqualTo("DEAD");
    assertThat(dead.lastError()).isEqualTo(InboxWorker.MAX_ATTEMPTS_ERROR);
    assertThat(dead.attempts()).isEqualTo(9);
    assertThat(orderCreated.calls.get()).isZero();
  }

  @Test
  void unknownShopIs503UntilActiveMembershipProvisions() throws Exception {
    String shopId = "shop-new-" + UUID.randomUUID();
    byte[] order = envelope(id(), "order.created", shopId, "ord", 1, Map.of());
    HttpResult waiting = post(order, eventId(order), sign(CURRENT, now(), order), serviceToken);
    assertThat(waiting.status()).isEqualTo(503);
    assertThat(waiting.retryAfter()).isEqualTo("60");
    assertError(waiting, "TENANT_NOT_READY");
    assertThat(count("SELECT count(*) FROM tenant WHERE tsf_shop_id = ?", shopId)).isZero();

    byte[] suspended =
        envelope(
            id(), "membership.changed", shopId, shopId, 1, membershipData("SUSPENDED", 1, null));
    HttpResult notYet =
        post(suspended, eventId(suspended), sign(CURRENT, now(), suspended), serviceToken);
    assertThat(notYet.status()).isEqualTo(503);
    assertThat(count("SELECT count(*) FROM tenant WHERE tsf_shop_id = ?", shopId)).isZero();

    String membershipId = id();
    Map<String, Object> active = membershipData("ACTIVE", 4, future());
    active.put("name", "New Shop");
    byte[] membership = envelope(membershipId, "membership.changed", shopId, shopId, 1, active);
    assertThat(
            post(membership, membershipId, sign(CURRENT, now(), membership), serviceToken).status())
        .isEqualTo(202);
    assertThat(text("SELECT name FROM tenant WHERE tsf_shop_id = ?", shopId)).isEqualTo("New Shop");
    assertThat(text("SELECT entitlement_status FROM tenant WHERE tsf_shop_id = ?", shopId))
        .isEqualTo("ACTIVE");
    assertThat(count("SELECT ent_ver FROM tenant WHERE tsf_shop_id = ?", shopId)).isEqualTo(4);

    String orderId = id();
    byte[] ready = envelope(orderId, "order.created", shopId, "ord", 1, Map.of());
    assertThat(post(ready, orderId, sign(CURRENT, now(), ready), serviceToken).status())
        .isEqualTo(202);
    worker.processAvailable();
    assertThat(text("SELECT status FROM inbox_event WHERE event_id = ?", membershipId))
        .isEqualTo("PROCESSED");
    // The order may have been leased in the same claim. Reactivation moves that lease to now(),
    // so the claimant skips it and the next poll applies it.
    worker.processAvailable();
    assertThat(text("SELECT status FROM inbox_event WHERE event_id = ?", orderId))
        .isEqualTo("PROCESSED");
    assertThat(orderCreated.calls.get()).isEqualTo(1);
    assertThat(count("SELECT ent_ver FROM tenant WHERE tsf_shop_id = ?", shopId)).isEqualTo(4);
  }

  @Test
  void invalidMembershipGoesDeadWithoutRetry() throws Exception {
    Shop shop = seed("ACTIVE", future(), 1);
    String eventId = id();
    String raw =
        new String(
            envelope(
                eventId,
                "membership.changed",
                shop.shopId(),
                shop.shopId(),
                1,
                membershipData("ACTIVE", 1, future())),
            StandardCharsets.UTF_8);
    raw = raw.replace("\"ent_ver\":1", "\"ent_ver\":18446744073709551617");
    byte[] body = raw.getBytes(StandardCharsets.UTF_8);
    assertThat(post(body, eventId, sign(CURRENT, now(), body), serviceToken).status())
        .isEqualTo(202);
    assertThat(worker.processAvailable()).isEqualTo(1);
    InboxState dead = state(eventId);
    assertThat(dead.status()).isEqualTo("DEAD");
    assertThat(dead.attempts()).isEqualTo(1);
    assertThat(dead.lastError()).isEqualTo("membership.changed is invalid");
    assertThat(count("SELECT ent_ver FROM tenant WHERE id = ?::uuid", shop.id().toString()))
        .isEqualTo(1);
    assertThat(worker.processAvailable()).isZero();
  }

  private int awaitThenProcess(CountDownLatch start) throws InterruptedException {
    if (!start.await(5, TimeUnit.SECONDS)) {
      throw new IllegalStateException("start timed out");
    }
    return worker.processAvailable();
  }

  private int awaitThenProcessOne(CountDownLatch start) throws InterruptedException {
    if (!start.await(5, TimeUnit.SECONDS)) {
      throw new IllegalStateException("start timed out");
    }
    return worker.processAvailable(1);
  }

  private boolean awaitAdvisoryWaiter() throws Exception {
    for (int i = 0; i < 100; i++) {
      if (count("SELECT count(*) FROM pg_locks WHERE locktype = 'advisory' AND NOT granted") >= 1) {
        return true;
      }
      Thread.sleep(50);
    }
    return false;
  }

  private void postOrder(Shop shop, String eventId, String aggregateId, long version)
      throws Exception {
    byte[] body = envelope(eventId, "order.created", shop.shopId(), aggregateId, version, Map.of());
    assertThat(post(body, eventId, sign(CURRENT, now(), body), serviceToken).status())
        .isEqualTo(202);
  }

  private void postMembership(
      Shop shop, String eventId, String status, long entVer, Instant expires, long version)
      throws Exception {
    byte[] body =
        envelope(
            eventId,
            "membership.changed",
            shop.shopId(),
            shop.shopId(),
            version,
            membershipData(status, entVer, expires));
    assertThat(post(body, eventId, sign(CURRENT, now(), body), serviceToken).status())
        .isEqualTo(202);
  }

  private void stampReceived(String eventId, int secondsAgo) throws Exception {
    try (Connection admin = AuthTestSupport.admin();
        PreparedStatement statement =
            admin.prepareStatement(
                "UPDATE inbox_event SET received_at = now() - (? * interval '1 second') "
                    + "WHERE event_id = ?")) {
      statement.setInt(1, secondsAgo);
      statement.setString(2, eventId);
      assertThat(statement.executeUpdate()).isEqualTo(1);
    }
  }

  private OffsetDateTime nextAttemptAt(String eventId) throws Exception {
    try (Connection admin = AuthTestSupport.admin();
        PreparedStatement statement =
            admin.prepareStatement("SELECT next_attempt_at FROM inbox_event WHERE event_id = ?")) {
      statement.setString(1, eventId);
      try (ResultSet rows = statement.executeQuery()) {
        assertThat(rows.next()).isTrue();
        return rows.getObject(1, OffsetDateTime.class);
      }
    }
  }

  private UUID insertInbox(
      UUID tenantId,
      String eventId,
      String status,
      int attempts,
      Integer nextAttemptSecondsAgo,
      int receivedSecondsAgo,
      String lastError)
      throws Exception {
    UUID id = UUID.randomUUID();
    try (Connection admin = AuthTestSupport.admin();
        PreparedStatement statement =
            admin.prepareStatement(
                """
                INSERT INTO inbox_event (
                  id, tenant_id, source, event_id, event_type, aggregate_id, payload,
                  status, attempts, next_attempt_at, received_at, last_error
                ) VALUES (
                  ?, ?, 'tsf', ?, 'order.created', ?, '{}'::jsonb,
                  ?, ?,
                  CASE WHEN ? THEN pg_catalog.now() - (? * interval '1 second') END,
                  pg_catalog.now() - (? * interval '1 second'),
                  ?
                )
                """)) {
      statement.setObject(1, id);
      statement.setObject(2, tenantId);
      statement.setString(3, eventId);
      statement.setString(4, "agg-" + eventId);
      statement.setString(5, status);
      statement.setInt(6, attempts);
      statement.setBoolean(7, nextAttemptSecondsAgo != null);
      statement.setInt(8, nextAttemptSecondsAgo == null ? 0 : nextAttemptSecondsAgo);
      statement.setInt(9, receivedSecondsAgo);
      if (lastError == null) {
        statement.setNull(10, Types.VARCHAR);
      } else {
        statement.setString(10, lastError);
      }
      assertThat(statement.executeUpdate()).isEqualTo(1);
    }
    return id;
  }

  private List<UUID> claimOne() {
    return jdbc.query(
        "SELECT id FROM claim_inbox_batch(1, interval '5 minutes')",
        (rs, row) -> rs.getObject("id", UUID.class));
  }

  private void rewind(String eventId) throws Exception {
    try (Connection admin = AuthTestSupport.admin();
        PreparedStatement statement =
            admin.prepareStatement(
                "UPDATE inbox_event SET next_attempt_at = now() - interval '1 second' "
                    + "WHERE event_id = ?")) {
      statement.setString(1, eventId);
      assertThat(statement.executeUpdate()).isEqualTo(1);
    }
  }

  private Shop seed(String status, Instant expires, long entVer) throws Exception {
    UUID id = UUID.randomUUID();
    String shopId = "shop-" + id;
    try (Connection admin = AuthTestSupport.admin();
        PreparedStatement statement =
            admin.prepareStatement(
                "INSERT INTO tenant (id, name, tsf_shop_id, membership_tier, "
                    + "entitlement_status, entitlement_expires_at, ent_ver) "
                    + "VALUES (?, 'Shop', ?, 'PRO', ?, ?, ?)")) {
      statement.setObject(1, id);
      statement.setString(2, shopId);
      statement.setString(3, status);
      if (expires == null) {
        statement.setNull(4, Types.TIMESTAMP_WITH_TIMEZONE);
      } else {
        statement.setObject(4, OffsetDateTime.ofInstant(expires, ZoneOffset.UTC));
      }
      statement.setLong(5, entVer);
      statement.executeUpdate();
    }
    return new Shop(id, shopId);
  }

  private byte[] envelope(
      String eventId,
      String eventType,
      String shopId,
      String aggregateId,
      long version,
      Map<String, Object> data) {
    return envelope(eventId, eventType, shopId, aggregateId, version, true, data);
  }

  private byte[] envelopeWithoutVersion(
      String eventId,
      String eventType,
      String shopId,
      String aggregateId,
      Map<String, Object> data) {
    return envelope(eventId, eventType, shopId, aggregateId, 0, false, data);
  }

  private byte[] envelope(
      String eventId,
      String eventType,
      String shopId,
      String aggregateId,
      long version,
      boolean includeVersion,
      Map<String, Object> data) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("event_id", eventId);
    body.put("event_type", eventType);
    body.put("schema_version", 1);
    body.put("occurred_at", "2026-09-29T08:15:02Z");
    body.put("tsf_shop_id", shopId);
    body.put("aggregate_id", aggregateId);
    if (includeVersion) {
      body.put("aggregate_version", version);
    }
    body.put("data", data);
    return JSON.writeValueAsBytes(body);
  }

  private Map<String, Object> membershipData(String status, long entVer, Instant expires) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("tier", "PRO");
    data.put("status", status);
    data.put("ent_ver", entVer);
    data.put("expires_at", expires == null ? null : expires.toString());
    return data;
  }

  private HttpResult post(byte[] body, String eventId, String signature, String token)
      throws Exception {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/internal/v1/events"))
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/json")
            .header("X-Event-Id", eventId)
            .POST(HttpRequest.BodyPublishers.ofByteArray(body));
    if (signature != null) {
      request.header("X-Signature", signature);
    }
    if (token != null) {
      request.header("Authorization", "Bearer " + token);
    }
    HttpResponse<String> response =
        HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    return new HttpResult(
        response.statusCode(),
        response.body(),
        response.headers().firstValue("retry-after").orElse(null));
  }

  private static String sign(String secret, String timestamp, byte[] body) throws Exception {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    mac.update((timestamp + ".").getBytes(StandardCharsets.UTF_8));
    mac.update(body);
    return "t=" + timestamp + ",v1=" + HexFormat.of().formatHex(mac.doFinal());
  }

  private static void assertError(HttpResult result, String code) {
    JsonNode body = JSON.readTree(result.body());
    assertThat(body.path("error").asString()).isEqualTo(code);
    assertThat(body.path("message").asString()).isNotBlank();
    assertThat(body.path("trace_id").asString()).hasSize(32);
  }

  private InboxState state(String eventId) throws Exception {
    try (Connection admin = AuthTestSupport.admin();
        PreparedStatement statement =
            admin.prepareStatement(
                "SELECT status, attempts, last_error, "
                    + "EXTRACT(EPOCH FROM (next_attempt_at - now())) AS wait_seconds "
                    + "FROM inbox_event WHERE event_id = ?")) {
      statement.setString(1, eventId);
      try (ResultSet rows = statement.executeQuery()) {
        assertThat(rows.next()).isTrue();
        Object wait = rows.getObject("wait_seconds");
        return new InboxState(
            rows.getString("status"),
            rows.getInt("attempts"),
            rows.getString("last_error"),
            wait == null ? null : ((Number) wait).doubleValue());
      }
    }
  }

  private long count(String sql, String... args) throws Exception {
    try (Connection admin = AuthTestSupport.admin();
        PreparedStatement statement = admin.prepareStatement(sql)) {
      for (int i = 0; i < args.length; i++) {
        statement.setString(i + 1, args[i]);
      }
      try (ResultSet rows = statement.executeQuery()) {
        assertThat(rows.next()).isTrue();
        return rows.getLong(1);
      }
    }
  }

  private String text(String sql, String... args) throws Exception {
    try (Connection admin = AuthTestSupport.admin();
        PreparedStatement statement = admin.prepareStatement(sql)) {
      for (int i = 0; i < args.length; i++) {
        statement.setString(i + 1, args[i]);
      }
      try (ResultSet rows = statement.executeQuery()) {
        assertThat(rows.next()).isTrue();
        return rows.getString(1);
      }
    }
  }

  private String tenantName(UUID id) throws Exception {
    return text("SELECT name FROM tenant WHERE id = ?::uuid", id.toString());
  }

  private static String id() {
    return "evt-" + UUID.randomUUID();
  }

  private static String now() {
    return Long.toString(Instant.now().getEpochSecond());
  }

  private static Instant future() {
    return Instant.now().plus(30, ChronoUnit.DAYS);
  }

  private static String eventId(byte[] body) {
    return JSON.readTree(body).path("event_id").asString();
  }

  private record Shop(UUID id, String shopId) {}

  private record HttpResult(int status, String body, String retryAfter) {}

  private record InboxState(String status, int attempts, String lastError, Double waitSeconds) {}

  @TestConfiguration
  static class Handlers {

    @Bean(name = "orderCreated")
    EffectHandler orderCreated(JdbcTemplate jdbc) {
      return new EffectHandler("order.created", jdbc, false);
    }

    @Bean(name = "orderFail")
    EffectHandler orderFail(JdbcTemplate jdbc) {
      return new EffectHandler("order.fail", jdbc, true);
    }
  }

  static final class EffectHandler implements InboxHandler {

    final AtomicInteger calls = new AtomicInteger();
    final AtomicInteger inFlight = new AtomicInteger();
    final AtomicInteger maxInFlight = new AtomicInteger();
    final List<Boolean> gaps = java.util.Collections.synchronizedList(new ArrayList<>());
    final List<UUID> tenantsSeen = java.util.Collections.synchronizedList(new ArrayList<>());
    volatile boolean block;
    volatile CountDownLatch entered;
    volatile CountDownLatch release;

    private final String type;
    private final JdbcTemplate jdbc;
    private final boolean fail;

    EffectHandler(String type, JdbcTemplate jdbc, boolean fail) {
      this.type = type;
      this.jdbc = jdbc;
      this.fail = fail;
    }

    void reset() {
      calls.set(0);
      inFlight.set(0);
      maxInFlight.set(0);
      gaps.clear();
      tenantsSeen.clear();
      block = false;
      entered = new CountDownLatch(1);
      release = new CountDownLatch(1);
    }

    @Override
    public String eventType() {
      return type;
    }

    @Override
    public void handle(InboxMessage message) {
      calls.incrementAndGet();
      gaps.add(message.gap());
      tenantsSeen.add(message.tenantId());
      int now = inFlight.incrementAndGet();
      maxInFlight.accumulateAndGet(now, Math::max);
      try {
        jdbc.update(
            """
            INSERT INTO audit_log (id, tenant_id, actor_type, action, entity_type, entity_id)
            VALUES (?, ?, 'TSF', 'inbox.handled', 'inbox_event', ?)
            """,
            UuidV7.generate(),
            message.tenantId(),
            message.eventId());
        if (fail) {
          jdbc.update(
              "UPDATE tenant SET name = ? WHERE id = ?", "should-rollback", message.tenantId());
          throw new IllegalStateException("handler failed");
        }
        if (block) {
          CountDownLatch arrived = entered;
          if (arrived != null) {
            arrived.countDown();
          }
          CountDownLatch gate = release;
          if (gate != null && !gate.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("release timed out");
          }
        }
      } catch (InterruptedException ex) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("interrupted");
      } finally {
        inFlight.decrementAndGet();
      }
    }
  }
}
