package com.thaishopfun.oms.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import com.thaishopfun.oms.auth.UuidV7;
import com.thaishopfun.oms.channel.api.CancelRequest;
import com.thaishopfun.oms.channel.api.CancelResponse;
import com.thaishopfun.oms.channel.api.LabelContent;
import com.thaishopfun.oms.channel.api.ListingPage;
import com.thaishopfun.oms.channel.api.OrderDetail;
import com.thaishopfun.oms.channel.api.OrderPage;
import com.thaishopfun.oms.channel.api.PaymentStatus;
import com.thaishopfun.oms.channel.api.Shipment;
import com.thaishopfun.oms.channel.api.ShipmentRequest;
import com.thaishopfun.oms.channel.exception.ChannelRateLimitedException;
import com.thaishopfun.oms.channel.exception.ChannelServerErrorException;
import com.thaishopfun.oms.channel.exception.ChannelUnavailableException;
import com.thaishopfun.oms.channel.exception.UnsupportedCapabilityException;
import com.thaishopfun.oms.channel.tsf.TsfChannelAdapter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link BaseChannelAdapter} resilience decorators. No Spring context. */
class ChannelResilienceTest {

  private final List<HttpServer> servers = new ArrayList<>();
  private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

  @AfterEach
  void stopServers() {
    for (HttpServer server : servers) {
      server.stop(0);
    }
    servers.clear();
    meters.clear();
  }

  @Test
  void bulkheadPerAttemptAllowsParallelUpToLimit() throws Exception {
    ChannelProperties properties = properties();
    properties.getTsf().setBulkheadMaxConcurrent(4);
    properties.getTsf().setBulkheadMaxWait(Duration.ofSeconds(30));
    properties.getTsf().setRateLimitPerSecond(1000);
    AtomicInteger inFlight = new AtomicInteger();
    AtomicInteger peak = new AtomicInteger();
    TestChannelAdapter adapter =
        adapter(
            properties,
            new RecordingSleeper(),
            () -> {
              int now = inFlight.incrementAndGet();
              peak.updateAndGet(current -> Math.max(current, now));
              Thread.sleep(5);
              inFlight.decrementAndGet();
              return sampleOrder();
            });
    ExecutorService pool = Executors.newFixedThreadPool(40);
    try {
      List<Future<?>> futures = new ArrayList<>();
      ChannelAccountRef account = accountRef();
      for (int i = 0; i < 40; i++) {
        int index = i;
        futures.add(pool.submit(() -> adapter.getOrder(account, "order-" + index)));
      }
      for (Future<?> future : futures) {
        future.get(30, TimeUnit.SECONDS);
      }
      // Step 1: Bulkhead is acquired per attempt; 40 sequential waves at limit 4 all succeed.
      assertThat(peak.get()).isLessThanOrEqualTo(4);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void bulkheadLimitsConcurrentCalls() throws Exception {
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    ChannelProperties properties = properties();
    properties.getTsf().setBulkheadMaxConcurrent(1);
    properties.getTsf().setBulkheadMaxWait(Duration.ofMillis(200));
    TestChannelAdapter adapter =
        adapter(
            properties,
            new RecordingSleeper(),
            () -> {
              entered.countDown();
              release.await(5, TimeUnit.SECONDS);
              return sampleOrder();
            });
    ChannelAccountRef account = accountRef();

    // Step 1: One call holds the bulkhead until we release it.
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<?> first = pool.submit(() -> adapter.getOrder(account, "order-1"));
      assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
      Future<?> second = pool.submit(() -> adapter.getOrder(account, "order-2"));
      assertThatThrownBy(() -> second.get(5, TimeUnit.SECONDS))
          .hasCauseInstanceOf(ChannelUnavailableException.class)
          .hasMessageContaining("bulkhead");
      release.countDown();
      first.get(5, TimeUnit.SECONDS);
    } finally {
      pool.shutdownNow();
    }
    assertThat(meters.find("oms.channel.calls").tag("outcome", "bulkhead_rejected").counter())
        .isNotNull();
  }

  @Test
  void circuitBreakerOpensAfterRepeatedUnavailableResponses() {
    AtomicInteger attempts = new AtomicInteger();
    ChannelProperties properties = properties();
    properties.getTsf().setCircuitMinimumNumberOfCalls(2);
    properties.getTsf().setCircuitSlidingWindowSize(2);
    properties.getTsf().setCircuitFailureRateThreshold(50);
    properties.getTsf().setRetryMaxAttempts(1);
    TestChannelAdapter adapter =
        adapter(
            properties,
            new RecordingSleeper(),
            () -> {
              attempts.incrementAndGet();
              throw new ChannelUnavailableException("down");
            });

    ChannelAccountRef account = accountRef();
    // Step 1: Two failures fill the window and open the circuit.
    assertThatThrownBy(() -> adapter.getOrder(account, "a"))
        .isInstanceOf(ChannelUnavailableException.class);
    assertThatThrownBy(() -> adapter.getOrder(account, "b"))
        .isInstanceOf(ChannelUnavailableException.class);
    int beforeOpen = attempts.get();
    assertThatThrownBy(() -> adapter.getOrder(account, "c"))
        .isInstanceOf(ChannelUnavailableException.class)
        .hasMessageContaining("circuit");
    // Step 2: An open circuit must not invoke the downstream stub again.
    assertThat(attempts).hasValue(beforeOpen);
    assertThat(meters.find("oms.channel.calls").tag("outcome", "circuit_open").counter())
        .isNotNull();
  }

  @Test
  void circuitBreakerRecoversFromHalfOpen() throws Exception {
    ChannelProperties properties = properties();
    properties.getTsf().setCircuitMinimumNumberOfCalls(2);
    properties.getTsf().setCircuitSlidingWindowSize(2);
    properties.getTsf().setCircuitFailureRateThreshold(50);
    properties.getTsf().setCircuitWaitInOpenState(Duration.ofMillis(150));
    properties.getTsf().setRetryMaxAttempts(1);
    AtomicInteger attempts = new AtomicInteger();
    TestChannelAdapter adapter =
        adapter(
            properties,
            new RecordingSleeper(),
            () -> {
              if (attempts.incrementAndGet() <= 2) {
                throw new ChannelUnavailableException("down");
              }
              return sampleOrder();
            });
    ChannelAccountRef account = accountRef();
    // Step 1: Open the breaker, wait for half-open, then succeed on the probe call.
    assertThatThrownBy(() -> adapter.getOrder(account, "a"))
        .isInstanceOf(ChannelUnavailableException.class);
    assertThatThrownBy(() -> adapter.getOrder(account, "b"))
        .isInstanceOf(ChannelUnavailableException.class);
    assertThatThrownBy(() -> adapter.getOrder(account, "c"))
        .isInstanceOf(ChannelUnavailableException.class)
        .hasMessageContaining("circuit");
    Thread.sleep(200);
    OrderDetail recovered = adapter.getOrder(account, "d");
    assertThat(recovered.orderId()).isEqualTo("TSF-1");
    assertThat(attempts.get()).isGreaterThanOrEqualTo(3);
  }

  @Test
  void circuitBreakerIsIsolatedPerAccount() {
    ChannelProperties properties = properties();
    properties.getTsf().setCircuitMinimumNumberOfCalls(2);
    properties.getTsf().setCircuitSlidingWindowSize(2);
    properties.getTsf().setCircuitFailureRateThreshold(50);
    properties.getTsf().setRetryMaxAttempts(1);
    AtomicInteger sickAttempts = new AtomicInteger();
    TestChannelAdapter sickAdapter =
        adapter(
            properties,
            new RecordingSleeper(),
            () -> {
              sickAttempts.incrementAndGet();
              throw new ChannelUnavailableException("down");
            });
    TestChannelAdapter healthyAdapter =
        adapter(properties, new RecordingSleeper(), () -> sampleOrder());
    ChannelAccountRef sick = accountRef();
    ChannelAccountRef healthy = accountRef();
    assertThatThrownBy(() -> sickAdapter.getOrder(sick, "a"))
        .isInstanceOf(ChannelUnavailableException.class);
    assertThatThrownBy(() -> sickAdapter.getOrder(sick, "b"))
        .isInstanceOf(ChannelUnavailableException.class);
    assertThatThrownBy(() -> sickAdapter.getOrder(sick, "c"))
        .isInstanceOf(ChannelUnavailableException.class)
        .hasMessageContaining("circuit");
    int attemptsAfterOpen = sickAttempts.get();
    // Step 1: A different channel account keeps its own breaker window.
    assertThat(healthyAdapter.getOrder(healthy, "ok").orderId()).isEqualTo("TSF-1");
    assertThat(sickAttempts.get()).isEqualTo(attemptsAfterOpen);
  }

  @Test
  void twoAccountsShareChannelBulkhead() throws Exception {
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    ChannelProperties properties = properties();
    properties.getTsf().setBulkheadMaxConcurrent(1);
    properties.getTsf().setBulkheadMaxWait(Duration.ofMillis(200));
    properties.getTsf().setRateLimitPerSecond(1000);
    TestChannelAdapter adapter =
        adapter(
            properties,
            new RecordingSleeper(),
            () -> {
              entered.countDown();
              release.await(5, TimeUnit.SECONDS);
              return sampleOrder();
            });
    ChannelAccountRef first = accountRef();
    ChannelAccountRef second = accountRef();
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<?> one = pool.submit(() -> adapter.getOrder(first, "one"));
      assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
      Future<?> two = pool.submit(() -> adapter.getOrder(second, "two"));
      assertThatThrownBy(() -> two.get(5, TimeUnit.SECONDS))
          .hasCauseInstanceOf(ChannelUnavailableException.class)
          .hasMessageContaining("bulkhead");
      release.countDown();
      one.get(5, TimeUnit.SECONDS);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void retryBackoffUsesFullJitterWithinBounds() throws Exception {
    RecordingSleeper sleeper = new RecordingSleeper();
    ChannelProperties properties = properties();
    properties.getTsf().setRetryMaxAttempts(4);
    properties.getTsf().setRetryWaitBase(Duration.ofMillis(100));
    properties.getTsf().setRetryWaitMax(Duration.ofMillis(100));
    AtomicInteger attempts = new AtomicInteger();
    TestChannelAdapter adapter =
        adapter(
            properties,
            sleeper,
            () -> {
              if (attempts.incrementAndGet() < 4) {
                throw new ChannelUnavailableException("retry");
              }
              return sampleOrder();
            });
    adapter.getOrder(accountRef(), "jitter");
    // Step 1: Each backoff sleep is in [0, retryWaitMax].
    assertThat(sleeper.durations()).hasSize(3);
    for (Duration sleep : sleeper.durations()) {
      assertThat(sleep.compareTo(Duration.ZERO)).isGreaterThanOrEqualTo(0);
      assertThat(sleep.compareTo(Duration.ofMillis(100))).isLessThanOrEqualTo(0);
    }
  }

  @Test
  void attemptDurationCountsAgainstBudget() {
    java.util.concurrent.atomic.AtomicReference<Instant> now =
        new java.util.concurrent.atomic.AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));
    Clock clock =
        new Clock() {
          @Override
          public ZoneId getZone() {
            return ZoneOffset.UTC;
          }

          @Override
          public Clock withZone(ZoneId zone) {
            return this;
          }

          @Override
          public Instant instant() {
            return now.get();
          }
        };
    ChannelProperties properties = properties();
    properties.getTsf().setCallTimeBudget(Duration.ofMillis(50));
    properties.getTsf().setRetryMaxAttempts(3);
    properties.getTsf().setRetryWaitBase(Duration.ofMillis(100));
    properties.getTsf().setRetryWaitMax(Duration.ofMillis(100));
    TestChannelAdapter adapter =
        new TestChannelAdapter(
            new AccountResilienceRegistry(properties, new ChannelMetrics(meters)),
            properties,
            new ChannelMetrics(meters),
            new RecordingSleeper(),
            clock,
            () -> {
              now.set(now.get().plus(Duration.ofMillis(60)));
              throw new ChannelUnavailableException("retry");
            });
    // Step 1: The first retry sleep would exceed the wall-clock budget.
    assertThatThrownBy(() -> adapter.getOrder(accountRef(), "budget"))
        .isInstanceOf(ChannelUnavailableException.class)
        .hasMessageContaining("budget");
  }

  @Test
  void retryAfterExceedingCallBudgetThrowsWithoutSleep() throws Exception {
    AtomicInteger attempts = new AtomicInteger();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    attachTokenHandler(server);
    server.createContext(
        "/internal/v1/orders/TSF-429-BUDGET",
        exchange -> {
          attempts.incrementAndGet();
          byte[] body = "{\"error\":\"RATE_LIMITED\"}".getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.getResponseHeaders().set("Retry-After", "1");
          exchange.sendResponseHeaders(429, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.start();
    servers.add(server);
    Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    RecordingSleeper sleeper = new RecordingSleeper();
    ChannelProperties properties = properties();
    properties.getTsf().setCallTimeBudget(Duration.ofMillis(50));
    properties.getTsf().setMaxRetryAfter(Duration.ofSeconds(60));
    properties.getTsf().setRetryMaxAttempts(3);
    TsfChannelAdapter adapter =
        tsfAdapter(server.getAddress().getPort(), properties, sleeper, clock);
    assertThatThrownBy(() -> adapter.getOrder(accountRef(), "TSF-429-BUDGET"))
        .isInstanceOf(ChannelRateLimitedException.class);
    assertThat(sleeper.durations()).isEmpty();
    assertThat(attempts.get()).isEqualTo(1);
  }

  @Test
  void retryAfterFromHttp429WaitsBeforeSuccess() throws Exception {
    AtomicInteger hits = new AtomicInteger();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/token",
        exchange -> {
          byte[] body =
              "{\"access_token\":\"test-token\",\"expires_in\":3600}"
                  .getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.createContext(
        "/internal/v1/orders/TSF-1",
        exchange -> {
          if (hits.getAndIncrement() == 0) {
            byte[] body = "{\"error\":\"RATE_LIMITED\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.getResponseHeaders().set("Retry-After", "2");
            exchange.sendResponseHeaders(429, body.length);
            exchange.getResponseBody().write(body);
          } else {
            byte[] body =
                """
                {"order_id":"TSF-1","reservation_id":"rsv","payment_method":"PREPAID",\
                "currency":"THB","lines":[],"updated_at":"2026-01-01T00:00:00Z",\
                "aggregate_version":1}
                """
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
          }
          exchange.close();
        });
    server.start();
    servers.add(server);
    RecordingSleeper sleeper = new RecordingSleeper();
    ChannelProperties properties = properties();
    properties.getTsf().setHttpTimeout(Duration.ofSeconds(30));
    properties.getTsf().setCallTimeBudget(Duration.ofSeconds(30));
    TsfChannelAdapter adapter = tsfAdapter(server.getAddress().getPort(), properties, sleeper);
    ChannelAccountRef ref = accountRef();

    // Step 1: First attempt gets 429 + Retry-After; adapter sleeps then succeeds.
    OrderDetail detail = adapter.getOrder(ref, "TSF-1");
    assertThat(detail.orderId()).isEqualTo("TSF-1");
    assertThat(hits).hasValue(2);
    assertThat(sleepsAtLeast(sleeper, Duration.ofSeconds(2))).isTrue();
    assertThat(meters.find("oms.channel.retries").tag("reason", "retry_after").counter())
        .isNotNull();
  }

  @Test
  void recordingSleeperCapturesBackoffDelays() throws Exception {
    RecordingSleeper sleeper = new RecordingSleeper();
    ChannelProperties properties = properties();
    properties.getTsf().setRetryMaxAttempts(2);
    properties.getTsf().setRetryWaitBase(Duration.ofMillis(100));
    properties.getTsf().setRetryWaitMax(Duration.ofMillis(100));
    AtomicInteger attempts = new AtomicInteger();
    TestChannelAdapter adapter =
        adapter(
            properties,
            sleeper,
            () -> {
              if (attempts.incrementAndGet() < 2) {
                throw new ChannelUnavailableException("retry me");
              }
              return sampleOrder();
            });
    adapter.getOrder(accountRef(), "x");
    assertThat(attempts).hasValue(2);
    assertThat(sleeper.durations()).isNotEmpty();
  }

  @Test
  void unsupportedCapabilityThrowsBeforeBulkhead() {
    ChannelProperties properties = properties();
    TestChannelAdapter adapter =
        new TestChannelAdapter(
            new AccountResilienceRegistry(properties, new ChannelMetrics(meters)),
            properties,
            new ChannelMetrics(meters),
            new RecordingSleeper(),
            Clock.systemUTC(),
            () -> sampleOrder(),
            new ChannelCapabilities(false, false, false, false, false, false, false, false));
    // Step 1: Capability check runs before any HTTP or resilience decoration.
    assertThatThrownBy(() -> adapter.listOrders(accountRef(), null, null, 10))
        .isInstanceOf(UnsupportedCapabilityException.class);
  }

  @Test
  void tsfCapabilitiesMatchContract() {
    TestChannelAdapter adapter = adapter(properties(), new RecordingSleeper(), () -> sampleOrder());
    ChannelCapabilities caps = adapter.capabilities();
    // Step 1: TSF supports every flow except partial shipment.
    assertThat(caps.supportsPartialShipment()).isFalse();
    assertThat(caps.supportsOrderPull()).isTrue();
    assertThatThrownBy(
            () ->
                adapter.createShipment(
                    accountRef(), "o", "key", new ShipmentRequest("FLASH", true)))
        .isInstanceOf(UnsupportedCapabilityException.class);
  }

  @Test
  void rateLimiterRejectsBurstWithoutRetryingHttp() {
    ChannelProperties properties = properties();
    properties.getTsf().setRateLimitPerSecond(1);
    properties.getTsf().setRateLimitWait(Duration.ofMillis(5));
    properties.getTsf().setRetryMaxAttempts(3);
    AtomicInteger calls = new AtomicInteger();
    TestChannelAdapter adapter =
        adapter(
            properties,
            new RecordingSleeper(),
            () -> {
              calls.incrementAndGet();
              return sampleOrder();
            });
    ChannelAccountRef account = accountRef();
    adapter.getOrder(account, "1");
    // Step 1: Rate limiter failure is not retried; the HTTP stub runs once.
    assertThatThrownBy(() -> adapter.getOrder(account, "2"))
        .isInstanceOf(ChannelRateLimitedException.class);
    assertThat(calls).hasValue(1);
    assertThat(meters.find("oms.channel.calls").tag("outcome", "rate_limited").counter())
        .isNotNull();
  }

  @Test
  void sustainedHttp500OpensCircuitWithoutRetrying() throws Exception {
    AtomicInteger fiveHundredHits = new AtomicInteger();
    HttpServer server500 = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server500.createContext(
        "/token",
        exchange -> {
          byte[] body =
              "{\"access_token\":\"test-token\",\"expires_in\":3600}"
                  .getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server500.createContext(
        "/internal/v1/orders/TSF-500",
        exchange -> {
          fiveHundredHits.incrementAndGet();
          byte[] body = "{\"error\":\"INTERNAL\"}".getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(500, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server500.start();
    servers.add(server500);
    ChannelProperties properties = properties();
    properties.getTsf().setCircuitMinimumNumberOfCalls(2);
    properties.getTsf().setCircuitSlidingWindowSize(2);
    properties.getTsf().setCircuitFailureRateThreshold(50);
    properties.getTsf().setRetryMaxAttempts(3);
    TsfChannelAdapter adapter500 =
        tsfAdapter(server500.getAddress().getPort(), properties, new RecordingSleeper());
    ChannelAccountRef account = accountRef();
    assertThatThrownBy(() -> adapter500.getOrder(account, "TSF-500"))
        .isInstanceOf(ChannelServerErrorException.class);
    assertThat(fiveHundredHits).hasValue(1);
    assertThatThrownBy(() -> adapter500.getOrder(account, "TSF-500b"))
        .isInstanceOf(ChannelServerErrorException.class);
    assertThat(fiveHundredHits).hasValue(2);
    int beforeOpen = fiveHundredHits.get();
    assertThatThrownBy(() -> adapter500.getOrder(account, "TSF-500c"))
        .isInstanceOf(ChannelUnavailableException.class)
        .hasMessageContaining("circuit");
    assertThat(fiveHundredHits).hasValue(beforeOpen);
  }

  @Test
  void http503IsRetried() throws Exception {
    AtomicInteger fiveOhThreeHits = new AtomicInteger();
    HttpServer server503 = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server503.createContext(
        "/token",
        exchange -> {
          byte[] body =
              "{\"access_token\":\"test-token\",\"expires_in\":3600}"
                  .getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server503.createContext(
        "/internal/v1/orders/TSF-503",
        exchange -> {
          int hit = fiveOhThreeHits.incrementAndGet();
          if (hit == 1) {
            byte[] body = "{\"error\":\"UNAVAILABLE\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(503, body.length);
            exchange.getResponseBody().write(body);
          } else {
            byte[] body =
                """
                {"order_id":"TSF-503","reservation_id":"rsv","payment_method":"PREPAID",\
                "currency":"THB","lines":[],"updated_at":"2026-01-01T00:00:00Z",\
                "aggregate_version":1}
                """
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
          }
          exchange.close();
        });
    server503.start();
    servers.add(server503);
    TsfChannelAdapter adapter503 =
        tsfAdapter(server503.getAddress().getPort(), properties(), new RecordingSleeper());
    OrderDetail detail = adapter503.getOrder(accountRef(), "TSF-503");
    assertThat(detail.orderId()).isEqualTo("TSF-503");
    assertThat(fiveOhThreeHits).hasValue(2);
  }

  @Test
  void slowStubTimeoutRetriesAndFinishesWithinBudget() throws Exception {
    AtomicInteger hits = new AtomicInteger();
    HttpServer server = slowOrderServer(hits, 6000, false);
    servers.add(server);
    ChannelProperties properties = properties();
    properties.getTsf().setCallTimeBudget(Duration.ofSeconds(5));
    properties.getTsf().setHttpTimeout(Duration.ofMillis(300));
    properties.getTsf().setRetryMaxAttempts(3);
    properties.getTsf().setRetryWaitBase(Duration.ofMillis(10));
    properties.getTsf().setRetryWaitMax(Duration.ofMillis(10));
    TsfChannelAdapter adapter =
        tsfAdapter(server.getAddress().getPort(), properties, new RecordingSleeper());
    Instant start = Instant.now();
    OrderDetail detail = adapter.getOrder(accountRef(), "TSF-SLOW");
    assertThat(detail.orderId()).isEqualTo("TSF-SLOW");
    assertThat(hits.get()).isGreaterThanOrEqualTo(2);
    assertThat(Duration.between(start, Instant.now())).isLessThanOrEqualTo(Duration.ofMillis(5500));
  }

  @Test
  void slowStubExhaustsBudgetWhenEveryAttemptTimesOut() throws Exception {
    AtomicInteger hits = new AtomicInteger();
    HttpServer server = slowOrderServer(hits, 6000, true);
    servers.add(server);
    ChannelProperties properties = properties();
    properties.getTsf().setCallTimeBudget(Duration.ofSeconds(5));
    properties.getTsf().setHttpTimeout(Duration.ofMillis(300));
    properties.getTsf().setRetryMaxAttempts(3);
    Instant start = Instant.now();
    TsfChannelAdapter adapter =
        tsfAdapter(server.getAddress().getPort(), properties, new RecordingSleeper());
    assertThatThrownBy(() -> adapter.getOrder(accountRef(), "TSF-SLOW"))
        .isInstanceOf(ChannelUnavailableException.class);
    assertThat(Duration.between(start, Instant.now())).isLessThanOrEqualTo(Duration.ofMillis(5500));
  }

  @Test
  void tokenThenHttpDelayRespectOneSecondCallBudget() throws Exception {
    long delayMs = 800;
    HttpServer server = delayedTokenAndOrderServer(delayMs, delayMs);
    servers.add(server);
    ChannelProperties properties = properties();
    properties.getTsf().setCallTimeBudget(Duration.ofSeconds(1));
    properties.getTsf().setHttpTimeout(Duration.ofSeconds(10));
    properties.getTsf().setRetryMaxAttempts(1);
    TsfChannelAdapter adapter =
        tsfAdapter(server.getAddress().getPort(), properties, new RecordingSleeper());
    Instant start = Instant.now();
    assertThatThrownBy(() -> adapter.getOrder(accountRef(), "TSF-DELAY"))
        .isInstanceOf(ChannelUnavailableException.class);
    assertThat(Duration.between(start, Instant.now())).isLessThanOrEqualTo(Duration.ofMillis(1300));
  }

  @Test
  void alwaysSlowStubWithLongHttpTimeoutFinishesWithinOneSecondBudget() throws Exception {
    AtomicInteger hits = new AtomicInteger();
    HttpServer server = slowOrderServer(hits, 6000, true);
    servers.add(server);
    ChannelProperties properties = properties();
    properties.getTsf().setCallTimeBudget(Duration.ofSeconds(1));
    properties.getTsf().setHttpTimeout(Duration.ofSeconds(10));
    properties.getTsf().setRetryMaxAttempts(3);
    Instant start = Instant.now();
    TsfChannelAdapter adapter =
        tsfAdapter(server.getAddress().getPort(), properties, new RecordingSleeper());
    assertThatThrownBy(() -> adapter.getOrder(accountRef(), "TSF-SLOW"))
        .isInstanceOf(ChannelUnavailableException.class);
    assertThat(Duration.between(start, Instant.now())).isLessThanOrEqualTo(Duration.ofMillis(1500));
  }

  @Test
  void retryAfterSleepDoesNotHoldBulkheadPermit() throws Exception {
    AtomicInteger bhHits = new AtomicInteger();
    AtomicInteger fastHits = new AtomicInteger();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    attachTokenHandler(server);
    server.createContext(
        "/internal/v1/orders/TSF-BH",
        exchange -> {
          if (bhHits.getAndIncrement() == 0) {
            byte[] body = "{\"error\":\"RATE_LIMITED\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.getResponseHeaders().set("Retry-After", "3");
            exchange.sendResponseHeaders(429, body.length);
            exchange.getResponseBody().write(body);
          } else {
            byte[] body =
                """
                {"order_id":"TSF-BH","reservation_id":"rsv","payment_method":"PREPAID",\
                "currency":"THB","lines":[],"updated_at":"2026-01-01T00:00:00Z",\
                "aggregate_version":1}
                """
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
          }
          exchange.close();
        });
    server.createContext(
        "/internal/v1/orders/TSF-BH-2",
        exchange -> {
          fastHits.incrementAndGet();
          byte[] body =
              """
              {"order_id":"TSF-BH-2","reservation_id":"rsv","payment_method":"PREPAID",\
              "currency":"THB","lines":[],"updated_at":"2026-01-01T00:00:00Z",\
              "aggregate_version":1}
              """
                  .getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.start();
    servers.add(server);
    ChannelProperties properties = properties();
    properties.getTsf().setBulkheadMaxConcurrent(1);
    properties.getTsf().setBulkheadMaxWait(Duration.ofMillis(500));
    properties.getTsf().setCallTimeBudget(Duration.ofSeconds(30));
    SystemSleeper realSleeper = new SystemSleeper();
    TsfChannelAdapter adapter = tsfAdapter(server.getAddress().getPort(), properties, realSleeper);
    ChannelAccountRef account = accountRef();
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Instant parallelStart = Instant.now();
      Future<OrderDetail> first = pool.submit(() -> adapter.getOrder(account, "TSF-BH"));
      Thread.sleep(100);
      Future<OrderDetail> second = pool.submit(() -> adapter.getOrder(account, "TSF-BH-2"));
      assertThat(second.get(5, TimeUnit.SECONDS).orderId()).isEqualTo("TSF-BH-2");
      assertThat(Duration.between(parallelStart, Instant.now())).isLessThan(Duration.ofSeconds(1));
      assertThat(first.get(15, TimeUnit.SECONDS).orderId()).isEqualTo("TSF-BH");
      assertThat(fastHits).hasValue(1);
      assertThat(bhHits).hasValue(2);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void bulkheadAllowsConcurrentCallsWithinLimit() throws Exception {
    runBulkheadOverlapConcurrencyTest(accountRef());
    runBulkheadOverlapConcurrencyTest(accountRef());
  }

  private void runBulkheadOverlapConcurrencyTest(ChannelAccountRef account) throws Exception {
    AtomicInteger inFlight = new AtomicInteger();
    AtomicInteger peak = new AtomicInteger();
    CountDownLatch allEntered = new CountDownLatch(4);
    ChannelProperties properties = properties();
    properties.getTsf().setBulkheadMaxConcurrent(4);
    properties.getTsf().setBulkheadMaxWait(Duration.ofSeconds(5));
    properties.getTsf().setRateLimitPerSecond(1000);
    TestChannelAdapter adapter =
        adapter(
            properties,
            new RecordingSleeper(),
            () -> {
              int now = inFlight.incrementAndGet();
              peak.updateAndGet(current -> Math.max(current, now));
              allEntered.countDown();
              Thread.sleep(1000);
              inFlight.decrementAndGet();
              return sampleOrder();
            });
    ExecutorService pool = Executors.newFixedThreadPool(4);
    try {
      Instant start = Instant.now();
      List<Future<?>> futures = new ArrayList<>();
      for (int i = 0; i < 4; i++) {
        int index = i;
        futures.add(pool.submit(() -> adapter.getOrder(account, "c-" + index)));
      }
      assertThat(allEntered.await(5, TimeUnit.SECONDS)).isTrue();
      for (Future<?> future : futures) {
        future.get(10, TimeUnit.SECONDS);
      }
      assertThat(Duration.between(start, Instant.now())).isLessThan(Duration.ofSeconds(2));
      assertThat(peak.get()).isGreaterThanOrEqualTo(2);
    } finally {
      pool.shutdownNow();
    }
  }

  private HttpServer delayedTokenAndOrderServer(long tokenDelayMs, long orderDelayMs)
      throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/token",
        exchange ->
            respondAfterDelay(
                exchange,
                tokenDelayMs,
                """
                {"access_token":"test-token","expires_in":3600}
                """,
                200));
    server.createContext(
        "/internal/v1/orders/TSF-DELAY",
        exchange ->
            respondAfterDelay(
                exchange,
                orderDelayMs,
                """
                {"order_id":"TSF-DELAY","reservation_id":"rsv","payment_method":"PREPAID",\
                "currency":"THB","lines":[],"updated_at":"2026-01-01T00:00:00Z",\
                "aggregate_version":1}
                """,
                200));
    server.start();
    return server;
  }

  private static void respondAfterDelay(
      com.sun.net.httpserver.HttpExchange exchange, long delayMs, String jsonBody, int status) {
    Thread delayed =
        new Thread(
            () -> {
              try {
                Thread.sleep(delayMs);
                byte[] body = jsonBody.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
              } catch (Exception ignored) {
                try {
                  exchange.close();
                } catch (Exception ignoredClose) {
                  // ignore
                }
              }
            },
            "delayed-stub");
    delayed.setDaemon(true);
    delayed.start();
  }

  private HttpServer slowOrderServer(AtomicInteger hits, long delayMs, boolean alwaysSlow)
      throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    attachTokenHandler(server);
    server.createContext(
        "/internal/v1/orders/TSF-SLOW",
        exchange -> {
          int hit = hits.getAndIncrement();
          if (hit == 0 || alwaysSlow) {
            Thread delayed =
                new Thread(
                    () -> {
                      try {
                        Thread.sleep(delayMs);
                        if (!alwaysSlow) {
                          writeSlowOrderResponse(exchange);
                        } else {
                          exchange.close();
                        }
                      } catch (Exception ignored) {
                        exchange.close();
                      }
                    },
                    "slow-stub-" + hit);
            delayed.setDaemon(true);
            delayed.start();
            return;
          }
          writeSlowOrderResponse(exchange);
        });
    server.start();
    return server;
  }

  private static void writeSlowOrderResponse(com.sun.net.httpserver.HttpExchange exchange)
      throws java.io.IOException {
    byte[] body =
        """
        {"order_id":"TSF-SLOW","reservation_id":"rsv","payment_method":"PREPAID",\
        "currency":"THB","lines":[],"updated_at":"2026-01-01T00:00:00Z",\
        "aggregate_version":1}
        """
            .getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(200, body.length);
    exchange.getResponseBody().write(body);
    exchange.close();
  }

  @Test
  void rateLimiterSpreadsBurstAcrossWallTime() throws Exception {
    ChannelProperties properties = properties();
    properties.getTsf().setRateLimitPerSecond(10);
    properties.getTsf().setRateLimitWait(Duration.ofSeconds(30));
    properties.getTsf().setRetryMaxAttempts(1);
    AtomicInteger calls = new AtomicInteger();
    TestChannelAdapter adapter =
        adapter(
            properties,
            new SystemSleeper(),
            () -> {
              calls.incrementAndGet();
              return sampleOrder();
            });
    ChannelAccountRef account = accountRef();
    Instant start = Instant.now();
    for (int i = 0; i < 30; i++) {
      adapter.getOrder(account, "order-" + i);
    }
    assertThat(Duration.between(start, Instant.now()))
        .isGreaterThanOrEqualTo(Duration.ofMillis(1500));
    assertThat(calls).hasValue(30);

    ChannelAccountRef other = accountRef();
    Instant otherStart = Instant.now();
    adapter.getOrder(other, "other");
    assertThat(Duration.between(otherStart, Instant.now())).isLessThan(Duration.ofSeconds(1));
  }

  @Test
  void channelMetricsUseOnlyDocumentedTagKeys() {
    TestChannelAdapter adapter = adapter(properties(), new RecordingSleeper(), () -> sampleOrder());
    adapter.getOrder(accountRef(), "metrics-order");
    for (var meter : meters.getMeters()) {
      String name = meter.getId().getName();
      if (!name.startsWith("oms.channel.")) {
        continue;
      }
      java.util.Set<String> keys =
          meter.getId().getTags().stream()
              .map(io.micrometer.core.instrument.Tag::getKey)
              .collect(java.util.stream.Collectors.toSet());
      if (name.equals("oms.channel.calls")) {
        assertThat(keys).containsExactlyInAnyOrder("channel", "operation", "outcome");
      } else if (name.equals("oms.channel.call.duration")) {
        assertThat(keys).containsExactlyInAnyOrder("channel", "operation");
      } else if (name.equals("oms.channel.retries")) {
        assertThat(keys).containsExactlyInAnyOrder("channel", "operation", "reason");
      } else if (name.equals("oms.channel.circuit.state")
          || name.equals("oms.channel.bulkhead.waiting")) {
        assertThat(keys).containsExactly("channel");
      }
    }
  }

  @Test
  void interruptFlagIsRestoredWhenRetrySleepIsInterrupted() throws Exception {
    ChannelProperties properties = properties();
    properties.getTsf().setRetryMaxAttempts(3);
    AtomicInteger attempts = new AtomicInteger();
    TestChannelAdapter adapter =
        adapter(
            properties,
            duration -> {
              throw new InterruptedException("stop");
            },
            () -> {
              attempts.incrementAndGet();
              throw new ChannelUnavailableException("retry");
            });
    java.util.concurrent.atomic.AtomicBoolean interruptedOnWorker =
        new java.util.concurrent.atomic.AtomicBoolean();
    Thread runner =
        new Thread(
            () -> {
              try {
                adapter.getOrder(accountRef(), "interrupt");
              } catch (ChannelUnavailableException ex) {
                interruptedOnWorker.set(Thread.currentThread().isInterrupted());
              }
            });
    runner.start();
    runner.join(5000);
    assertThat(interruptedOnWorker).isTrue();
    assertThat(attempts.get()).isGreaterThanOrEqualTo(1);
  }

  @Test
  void metricsTagChannelOperationAndOutcome() {
    TestChannelAdapter adapter = adapter(properties(), new RecordingSleeper(), () -> sampleOrder());
    adapter.getOrder(accountRef(), "ok");
    // Step 1: Success path records channel, operation, and outcome tags.
    assertThat(
            meters
                .find("oms.channel.calls")
                .tag("channel", "TSF")
                .tag("operation", "getOrder")
                .tag("outcome", "success")
                .counter()
                .count())
        .isEqualTo(1.0);
    assertThat(meters.find("oms.channel.call.duration").tag("channel", "TSF").timer()).isNotNull();
  }

  private static boolean sleepsAtLeast(RecordingSleeper sleeper, Duration floor) {
    return sleeper.durations().stream().anyMatch(d -> d.compareTo(floor) >= 0);
  }

  private TsfChannelAdapter tsfAdapter(int port, ChannelProperties properties, Sleeper sleeper) {
    return tsfAdapter(port, properties, sleeper, Clock.systemUTC());
  }

  private TsfChannelAdapter tsfAdapter(
      int port, ChannelProperties properties, Sleeper sleeper, Clock clock) {
    TsfProperties tsf = new TsfProperties();
    tsf.setBaseUrl("http://127.0.0.1:" + port);
    tsf.setTokenUri("http://127.0.0.1:" + port + "/token");
    tsf.setClientId("oms-service");
    tsf.setClientSecret("secret");
    tsf.setAudience("tsf-internal");
    tools.jackson.databind.json.JsonMapper json =
        tools.jackson.databind.json.JsonMapper.builder().build();
    AccountResilienceRegistry resilience =
        new AccountResilienceRegistry(properties, new ChannelMetrics(meters));
    com.thaishopfun.oms.channel.tsf.TsfTokenProvider tokens =
        new com.thaishopfun.oms.channel.tsf.TsfTokenProvider(tsf, properties, json, clock);
    com.thaishopfun.oms.channel.tsf.TsfHttpTransport transport =
        new com.thaishopfun.oms.channel.tsf.TsfHttpTransport(tsf, tokens, json, clock, properties);
    return new TsfChannelAdapter(
        resilience, properties, new ChannelMetrics(meters), sleeper, clock, transport, json);
  }

  private TestChannelAdapter adapter(ChannelProperties properties, RecordingSleeper sleeper) {
    return adapter(properties, (Sleeper) sleeper, () -> sampleOrder());
  }

  private TestChannelAdapter adapter(
      ChannelProperties properties, RecordingSleeper sleeper, Callable<?> behavior) {
    return adapter(properties, (Sleeper) sleeper, behavior);
  }

  private TestChannelAdapter adapter(
      ChannelProperties properties, Sleeper sleeper, Callable<?> behavior) {
    return new TestChannelAdapter(
        new AccountResilienceRegistry(properties, new ChannelMetrics(meters)),
        properties,
        new ChannelMetrics(meters),
        sleeper,
        Clock.systemUTC(),
        behavior);
  }

  private static void attachTokenHandler(HttpServer server) {
    server.createContext(
        "/token",
        exchange -> {
          byte[] body =
              "{\"access_token\":\"test-token\",\"expires_in\":3600}"
                  .getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
  }

  private static ChannelProperties properties() {
    ChannelProperties properties = new ChannelProperties();
    properties.getTsf().setRetryMaxAttempts(3);
    properties.getTsf().setRetryWaitBase(Duration.ofMillis(10));
    properties.getTsf().setRetryWaitMax(Duration.ofMillis(20));
    properties.getTsf().setHttpTimeout(Duration.ofSeconds(2));
    properties.getTsf().setCallTimeBudget(Duration.ofSeconds(2));
    return properties;
  }

  private static ChannelAccountRef accountRef() {
    return new ChannelAccountRef(UuidV7.generate(), UuidV7.generate(), "shop_active");
  }

  private static OrderDetail sampleOrder() {
    return new OrderDetail(
        "TSF-1",
        "rsv",
        "PREPAID",
        null,
        "THB",
        null,
        null,
        null,
        List.of(),
        Instant.parse("2026-01-01T00:00:00Z"),
        1L);
  }

  public static final class RecordingSleeper implements Sleeper {
    private final List<Duration> durations = new ArrayList<>();

    @Override
    public void sleep(Duration duration) {
      if (duration != null && !duration.isZero() && !duration.isNegative()) {
        durations.add(duration);
      }
    }

    List<Duration> durations() {
      return durations;
    }
  }

  static final class TestChannelAdapter extends BaseChannelAdapter {
    private final Callable<?> behavior;
    private final ChannelCapabilities capabilities;

    TestChannelAdapter(
        AccountResilienceRegistry resilience,
        ChannelProperties properties,
        ChannelMetrics metrics,
        Sleeper sleeper,
        Clock clock,
        Callable<?> behavior) {
      this(
          resilience,
          properties,
          metrics,
          sleeper,
          clock,
          behavior,
          new ChannelCapabilities(true, true, true, true, true, true, false, true));
    }

    TestChannelAdapter(
        AccountResilienceRegistry resilience,
        ChannelProperties properties,
        ChannelMetrics metrics,
        Sleeper sleeper,
        Clock clock,
        Callable<?> behavior,
        ChannelCapabilities capabilities) {
      super(resilience, properties, metrics, sleeper, clock);
      this.behavior = behavior;
      this.capabilities = capabilities;
    }

    @Override
    public Channel channel() {
      return Channel.TSF;
    }

    @Override
    public ChannelCapabilities capabilities() {
      return capabilities;
    }

    @Override
    protected OrderPage doListOrders(
        ChannelAccountRef account,
        Instant updatedSince,
        String cursor,
        int limit,
        Instant deadline) {
      return invokeBehavior();
    }

    @Override
    protected OrderDetail doGetOrder(
        ChannelAccountRef account, String externalOrderId, Instant deadline) {
      return invokeBehavior();
    }

    @Override
    protected PaymentStatus doGetPaymentStatus(
        ChannelAccountRef account, String externalOrderId, Instant deadline) {
      return invokeBehavior();
    }

    @Override
    protected ListingPage doListListings(
        ChannelAccountRef account, String cursor, Instant deadline) {
      return invokeBehavior();
    }

    @Override
    protected Shipment doCreateShipment(
        ChannelAccountRef account,
        String externalOrderId,
        String idempotencyKey,
        ShipmentRequest request,
        Instant deadline) {
      return invokeBehavior();
    }

    @Override
    protected LabelContent doGetLabel(
        ChannelAccountRef account, String shipmentId, Instant deadline) {
      return invokeBehavior();
    }

    @Override
    protected CancelResponse doRequestCancel(
        ChannelAccountRef account,
        String externalOrderId,
        String idempotencyKey,
        CancelRequest request,
        Instant deadline) {
      return invokeBehavior();
    }

    @SuppressWarnings("unchecked")
    private <T> T invokeBehavior() {
      try {
        return (T) behavior.call();
      } catch (RuntimeException ex) {
        throw ex;
      } catch (Exception ex) {
        throw new ChannelUnavailableException("test adapter failed", ex);
      }
    }
  }
}
