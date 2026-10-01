package com.thaishopfun.oms.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sun.net.httpserver.HttpServer;
import com.thaishopfun.oms.auth.UuidV7;
import com.thaishopfun.oms.channel.api.CancelRequest;
import com.thaishopfun.oms.channel.api.OrderDetail;
import com.thaishopfun.oms.channel.api.ShipmentRequest;
import com.thaishopfun.oms.channel.exception.ChannelClientException;
import com.thaishopfun.oms.channel.exception.ChannelIdempotencyConflictException;
import com.thaishopfun.oms.channel.exception.ChannelRateLimitedException;
import com.thaishopfun.oms.channel.exception.UnsupportedCapabilityException;
import com.thaishopfun.oms.channel.tsf.TsfChannelAdapter;
import com.thaishopfun.oms.channel.tsf.TsfHttpTransport;
import com.thaishopfun.oms.channel.tsf.TsfTokenProvider;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.InetSocketAddress;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

/** Focused unit tests for TSF transport, token cache, and adapter guards. No Spring context. */
class ChannelAdapterUnitTest {

  private final List<HttpServer> servers = new ArrayList<>();
  private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
  private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

  @BeforeEach
  void attachLogs() {
    Logger logger = (Logger) LoggerFactory.getLogger(TsfTokenProvider.class);
    logs.start();
    logger.addAppender(logs);
    logger.setLevel(Level.WARN);
  }

  @AfterEach
  void stopServersAndDetachLogs() {
    for (HttpServer server : servers) {
      server.stop(0);
    }
    servers.clear();
    meters.clear();
    Logger logger = (Logger) LoggerFactory.getLogger(TsfTokenProvider.class);
    logger.detachAppender(logs);
    logs.list.clear();
  }

  @Test
  void retryAfterHttpDateUsesFixedClock() throws Exception {
    Instant fixed = Instant.parse("2026-01-01T00:00:00Z");
    Clock clock = Clock.fixed(fixed, ZoneOffset.UTC);
    String retryAt =
        DateTimeFormatter.RFC_1123_DATE_TIME.withLocale(Locale.US).format(
            ZonedDateTime.ofInstant(fixed.plusSeconds(3), ZoneOffset.UTC));
    AtomicInteger hits = new AtomicInteger();
    int port = startOrderServer(clock, hits, 429, "Retry-After", retryAt, null);
    ChannelResilienceTest.RecordingSleeper sleeper = new ChannelResilienceTest.RecordingSleeper();
    ChannelProperties properties = properties();
    properties.getTsf().setHttpTimeout(Duration.ofSeconds(30));
    TsfChannelAdapter adapter = tsfAdapter(port, properties, sleeper, clock);
    adapter.getOrder(accountRef(), "TSF-DATE");
    // Step 1: HTTP-date Retry-After is converted to a sleep of at least three seconds.
    assertThat(hits).hasValue(2);
    assertThat(sleeper.durations().stream().anyMatch(d -> d.compareTo(Duration.ofSeconds(3)) >= 0))
        .isTrue();
  }

  @Test
  void retryAfterAboveMaxDoesNotSleep() throws Exception {
    AtomicInteger hits = new AtomicInteger();
    int port =
        startOrderServer(
            Clock.systemUTC(), hits, 429, "Retry-After", "120", "{\"error\":\"RATE_LIMITED\"}");
    ChannelProperties properties = properties();
    properties.getTsf().setMaxRetryAfter(Duration.ofSeconds(5));
    properties.getTsf().setRetryMaxAttempts(3);
    ChannelResilienceTest.RecordingSleeper sleeper = new ChannelResilienceTest.RecordingSleeper();
    TsfChannelAdapter adapter = tsfAdapter(port, properties, sleeper, Clock.systemUTC());
    // Step 1: Retry-After above maxRetryAfter fails immediately without sleeping.
    assertThatThrownBy(() -> adapter.getOrder(accountRef(), "TSF-LONG"))
        .isInstanceOf(ChannelRateLimitedException.class);
    assertThat(hits).hasValue(1);
    assertThat(sleeper.durations()).isEmpty();
  }

  @Test
  void clientErrorsAnd409AreNotRetried() throws Exception {
    AtomicInteger notFoundHits = new AtomicInteger();
    HttpServer server404 = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    attachToken(server404);
    server404.createContext(
        "/internal/v1/orders/TSF-404",
        exchange -> {
          notFoundHits.incrementAndGet();
          byte[] body =
              "{\"error\":\"ORDER_NOT_FOUND\",\"message\":\"missing\"}"
                  .getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(404, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server404.start();
    servers.add(server404);
    TsfChannelAdapter adapter404 =
        tsfAdapter(
            server404.getAddress().getPort(),
            new ChannelResilienceTest.RecordingSleeper(),
            Clock.systemUTC());
    assertThatThrownBy(() -> adapter404.getOrder(accountRef(), "TSF-404"))
        .isInstanceOf(ChannelClientException.class);
    assertThat(notFoundHits).hasValue(1);

    AtomicInteger conflictHits = new AtomicInteger();
    HttpServer server409 = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    attachToken(server409);
    server409.createContext(
        "/internal/v1/orders/TSF-409/shipments",
        exchange -> {
          conflictHits.incrementAndGet();
          byte[] body =
              "{\"error\":\"IDEMPOTENCY_CONFLICT\",\"message\":\"reuse\"}"
                  .getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(409, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server409.start();
    servers.add(server409);
    TsfChannelAdapter adapter409 =
        tsfAdapter(
            server409.getAddress().getPort(),
            new ChannelResilienceTest.RecordingSleeper(),
            Clock.systemUTC());
    assertThatThrownBy(
            () ->
                adapter409.createShipment(
                    accountRef(), "TSF-409", "key-409", new ShipmentRequest("FLASH")))
        .isInstanceOf(ChannelIdempotencyConflictException.class);
    assertThat(conflictHits).hasValue(1);
  }

  @Test
  void tokenProviderCachesAcrossCalls() throws Exception {
    AtomicInteger tokenHits = new AtomicInteger();
    int port = startTokenAndOrderServer(tokenHits, new AtomicInteger(), 3600);
    TsfChannelAdapter adapter =
        tsfAdapter(port, new ChannelResilienceTest.RecordingSleeper(), Clock.systemUTC());
    ChannelAccountRef account = accountRef();
    for (int i = 0; i < 10; i++) {
      adapter.getOrder(account, "TSF-CACHE-" + i);
    }
    // Step 1: Ten API calls share one OAuth token fetch.
    assertThat(tokenHits).hasValue(1);
  }

  @Test
  void shortLivedTokenUsesSkewBeforeRefresh() throws Exception {
    AtomicInteger tokenHits = new AtomicInteger();
    AtomicInteger orderHits = new AtomicInteger();
    int port = startTokenAndOrderServer(tokenHits, orderHits, 10);
    Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    TsfChannelAdapter adapter =
        tsfAdapter(port, new ChannelResilienceTest.RecordingSleeper(), clock);
    adapter.getOrder(accountRef(), "TSF-SKEW-1");
    Clock later = Clock.fixed(Instant.parse("2026-01-01T00:00:09Z"), ZoneOffset.UTC);
    TsfChannelAdapter laterAdapter =
        tsfAdapter(port, new ChannelResilienceTest.RecordingSleeper(), later);
    laterAdapter.getOrder(accountRef(), "TSF-SKEW-2");
    // Step 1: A 10s token is refreshed after half its lifetime (5s skew).
    assertThat(tokenHits).hasValue(2);
    assertThat(orderHits).hasValue(2);
  }

  @Test
  void unauthorizedRefreshesTokenOnceThenFails() throws Exception {
    AtomicInteger tokenHits = new AtomicInteger();
    AtomicInteger orderHits = new AtomicInteger();
    int port = startUnauthorizedOrderServer(tokenHits, orderHits);
    TsfChannelAdapter adapter =
        tsfAdapter(port, new ChannelResilienceTest.RecordingSleeper(), Clock.systemUTC());
    assertThatThrownBy(() -> adapter.getOrder(accountRef(), "TSF-401"))
        .isInstanceOf(ChannelClientException.class);
    // Step 1: One 401 triggers a single token refresh before the terminal failure.
    assertThat(tokenHits).hasValue(2);
    assertThat(orderHits).hasValue(2);
  }

  @Test
  void tokenLogsNeverContainSecretOrAccessToken() throws Exception {
    int port =
        startTokenServer(
            new AtomicInteger(),
            400,
            "{\"error\":\"invalid_client\",\"error_description\":\"bad secret\"}");
    TsfProperties tsf = tsfProperties(port);
    tsf.setClientSecret("super-secret-value");
    JsonMapper json = JsonMapper.builder().build();
    TsfTokenProvider tokens = new TsfTokenProvider(tsf, json, Clock.systemUTC());
    assertThatThrownBy(tokens::accessToken).isInstanceOf(ChannelClientException.class);
    // Step 1: Warning logs must not echo the client secret or bearer token material.
    for (ILoggingEvent event : logs.list) {
      String formatted = event.getFormattedMessage();
      assertThat(formatted).doesNotContain("super-secret-value");
      assertThat(formatted.toLowerCase(Locale.ROOT)).doesNotContain("access_token");
    }
  }

  @Test
  void disabledCapabilitiesSkipHttp() {
    AtomicInteger calls = new AtomicInteger();
    ChannelProperties properties = properties();
    ChannelResilienceTest.TestChannelAdapter adapter =
        new ChannelResilienceTest.TestChannelAdapter(
            new AccountResilienceRegistry(properties, new ChannelMetrics(meters)),
            properties,
            new ChannelMetrics(meters),
            new ChannelResilienceTest.RecordingSleeper(),
            Clock.systemUTC(),
            () -> {
              calls.incrementAndGet();
              return sampleOrder();
            },
            new ChannelCapabilities(false, false, false, false, false, false, false, false));
    ChannelAccountRef account = accountRef();
    // Step 1: Each disabled flag rejects before the HTTP stub runs.
    assertThatThrownBy(() -> adapter.listOrders(account, null, null, 10))
        .isInstanceOf(UnsupportedCapabilityException.class);
    assertThatThrownBy(() -> adapter.getOrder(account, "x"))
        .isInstanceOf(UnsupportedCapabilityException.class);
    assertThatThrownBy(() -> adapter.getPaymentStatus(account, "x"))
        .isInstanceOf(UnsupportedCapabilityException.class);
    assertThatThrownBy(() -> adapter.listListings(account, null))
        .isInstanceOf(UnsupportedCapabilityException.class);
    assertThatThrownBy(
            () -> adapter.createShipment(account, "x", "k", new ShipmentRequest("FLASH")))
        .isInstanceOf(UnsupportedCapabilityException.class);
    assertThatThrownBy(() -> adapter.getLabel(account, "shp"))
        .isInstanceOf(UnsupportedCapabilityException.class);
    assertThatThrownBy(
            () -> adapter.requestCancel(account, "x", "k", new CancelRequest("buyer")))
        .isInstanceOf(UnsupportedCapabilityException.class);
    assertThat(calls).hasValue(0);
  }

  @Test
  void tsfAdapterCapabilityFlagsMatchContract() {
    TsfChannelAdapter adapter =
        tsfAdapter(0, new ChannelResilienceTest.RecordingSleeper(), Clock.systemUTC(), false);
    ChannelCapabilities caps = adapter.capabilities();
    // Step 1: TSF supports every flow except partial shipment.
    assertThat(caps.supportsWebhooks()).isTrue();
    assertThat(caps.supportsOrderPull()).isTrue();
    assertThat(caps.supportsStockPush()).isTrue();
    assertThat(caps.supportsCancelRequest()).isTrue();
    assertThat(caps.supportsLabel()).isTrue();
    assertThat(caps.supportsReturn()).isTrue();
    assertThat(caps.supportsPartialShipment()).isFalse();
    assertThat(caps.supportsCod()).isTrue();
  }

  @Test
  void blankIdempotencyKeyIsRejectedBeforeHttp() {
    TsfChannelAdapter adapter =
        tsfAdapter(0, new ChannelResilienceTest.RecordingSleeper(), Clock.systemUTC(), false);
    assertThatThrownBy(
            () -> adapter.createShipment(accountRef(), "TSF-1", "  ", new ShipmentRequest("FLASH")))
        .isInstanceOf(ChannelClientException.class)
        .hasMessageContaining("Idempotency-Key");
  }

  @Test
  void pathEncodingUsesPercentTwentyForSpaces() throws Exception {
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
        "/",
        exchange -> {
          hits.incrementAndGet();
          assertThat(exchange.getRequestURI().getRawPath()).contains("TSF%20ORDER");
          byte[] body =
              """
              {"order_id":"TSF ORDER","reservation_id":"rsv","payment_method":"PREPAID",\
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
    TsfChannelAdapter adapter =
        tsfAdapter(
            server.getAddress().getPort(),
            new ChannelResilienceTest.RecordingSleeper(),
            Clock.systemUTC());
    adapter.getOrder(accountRef(), "TSF ORDER");
    assertThat(hits).hasValue(1);
  }

  private int startOrderServer(
      Clock clock,
      AtomicInteger hits,
      int faultStatus,
      String retryHeader,
      String retryValue,
      String faultBody)
      throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    attachToken(server);
    server.createContext(
        "/internal/v1/orders/TSF-DATE",
        exchange -> {
          if (hits.getAndIncrement() == 0) {
            byte[] body =
                (faultBody == null ? "{\"error\":\"RATE_LIMITED\"}" : faultBody)
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            if (retryHeader != null && retryValue != null) {
              exchange.getResponseHeaders().set(retryHeader, retryValue);
            }
            exchange.sendResponseHeaders(faultStatus, body.length);
            exchange.getResponseBody().write(body);
          } else {
            byte[] body =
                """
                {"order_id":"TSF-DATE","reservation_id":"rsv","payment_method":"PREPAID",\
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
        "/internal/v1/orders/TSF-LONG",
        exchange -> {
          hits.incrementAndGet();
          byte[] body = "{\"error\":\"RATE_LIMITED\"}".getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.getResponseHeaders().set("Retry-After", retryValue == null ? "120" : retryValue);
          exchange.sendResponseHeaders(429, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.start();
    servers.add(server);
    return server.getAddress().getPort();
  }

  private int startTokenAndOrderServer(
      AtomicInteger tokenHits, AtomicInteger orderHits, int expiresIn) throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/token",
        exchange -> {
          tokenHits.incrementAndGet();
          byte[] body =
              ("{\"access_token\":\"token-" + tokenHits.get() + "\",\"expires_in\":" + expiresIn + "}")
                  .getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.createContext(
        "/internal/v1/orders/",
        exchange -> {
          orderHits.incrementAndGet();
          byte[] body =
              """
              {"order_id":"TSF-CACHE","reservation_id":"rsv","payment_method":"PREPAID",\
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
    return server.getAddress().getPort();
  }

  private int startUnauthorizedOrderServer(AtomicInteger tokenHits, AtomicInteger orderHits)
      throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/token",
        exchange -> {
          tokenHits.incrementAndGet();
          byte[] body =
              ("{\"access_token\":\"token-" + tokenHits.get() + "\",\"expires_in\":3600}")
                  .getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.createContext(
        "/internal/v1/orders/TSF-401",
        exchange -> {
          orderHits.incrementAndGet();
          byte[] body = "{\"error\":\"UNAUTHORIZED\"}".getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(401, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.start();
    servers.add(server);
    return server.getAddress().getPort();
  }

  private int startTokenServer(AtomicInteger tokenHits, int status, String body) throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/token",
        exchange -> {
          tokenHits.incrementAndGet();
          byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(status, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    server.start();
    servers.add(server);
    return server.getAddress().getPort();
  }

  private TsfChannelAdapter tsfAdapter(int port, Sleeper sleeper, Clock clock) {
    return tsfAdapter(port, properties(), sleeper, clock, port > 0);
  }

  private TsfChannelAdapter tsfAdapter(
      int port, ChannelProperties properties, Sleeper sleeper, Clock clock) {
    return tsfAdapter(port, properties, sleeper, clock, port > 0);
  }

  private TsfChannelAdapter tsfAdapter(
      int port, Sleeper sleeper, Clock clock, boolean bindServer) {
    return tsfAdapter(port, properties(), sleeper, clock, bindServer);
  }

  private TsfChannelAdapter tsfAdapter(
      int port,
      ChannelProperties properties,
      Sleeper sleeper,
      Clock clock,
      boolean bindServer) {
    TsfProperties tsf = tsfProperties(bindServer ? port : 1);
    JsonMapper json = JsonMapper.builder().build();
    AccountResilienceRegistry resilience =
        new AccountResilienceRegistry(properties, new ChannelMetrics(meters));
    TsfTokenProvider tokens = new TsfTokenProvider(tsf, json, clock);
    TsfHttpTransport transport = new TsfHttpTransport(tsf, tokens, json, clock);
    return new TsfChannelAdapter(
        resilience, properties, new ChannelMetrics(meters), sleeper, clock, transport, json);
  }

  private static TsfProperties tsfProperties(int port) {
    TsfProperties tsf = new TsfProperties();
    tsf.setBaseUrl("http://127.0.0.1:" + port);
    tsf.setTokenUri("http://127.0.0.1:" + port + "/token");
    tsf.setClientId("oms-service");
    tsf.setClientSecret("secret");
    tsf.setAudience("tsf-internal");
    return tsf;
  }

  private static ChannelProperties properties() {
    ChannelProperties properties = new ChannelProperties();
    properties.getTsf().setRetryMaxAttempts(3);
    properties.getTsf().setRetryWaitBase(Duration.ofMillis(10));
    properties.getTsf().setRetryWaitMax(Duration.ofMillis(20));
    properties.getTsf().setHttpTimeout(Duration.ofSeconds(2));
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

  private static void attachToken(HttpServer server) {
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

}
