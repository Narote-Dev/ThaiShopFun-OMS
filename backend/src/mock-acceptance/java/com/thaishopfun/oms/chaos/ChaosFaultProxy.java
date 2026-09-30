package com.thaishopfun.oms.chaos;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Localhost HTTP layer between the OMS outbox publisher and the mock-tsf receiver.
 *
 * <p>mock-tsf {@code /control/faults} refuses {@code /internal/v1/oms-events}, so faults for that
 * route live here. Each event id has a fixed list of faults, one per delivery attempt this proxy
 * sees. Attempts past the list are forwarded unchanged. Nothing is random and nothing depends on
 * which publisher thread sends the request.
 */
final class ChaosFaultProxy implements AutoCloseable {

  enum Fault {
    /** 503 with no Retry-After. The 4.4 ladder decides the delay. */
    HTTP_503,
    /** 503 with Retry-After longer than the first ladder slot. */
    HTTP_503_RETRY_AFTER,
    HTTP_500,
    /** 429 with Retry-After longer than the first ladder slot. */
    HTTP_429_RETRY_AFTER,
    /** Close the connection before the receiver sees the event. */
    RESET,
    /** Forward to the receiver, then close the connection without a response. */
    RESET_AFTER_FORWARD,
    /** Forward to the receiver, then hold the response until the publisher times out. */
    TIMEOUT_AFTER_FORWARD
  }

  /** The last fault answered for one event, used to check the scheduled retry time. */
  record Answered(Fault fault, Instant at, Integer retryAfterSeconds) {}

  /** Longer than the 30s first slot, and longer than any drain round, so it is always visible. */
  static final int RETRY_AFTER_429_SECONDS = 120;

  static final int RETRY_AFTER_503_SECONDS = 90;
  private static final Duration HOLD_LIMIT = Duration.ofSeconds(60);

  private final HttpServer server;
  private final ExecutorService executor;
  private final HttpClient client =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  private final URI target;

  private final Map<String, List<Fault>> plan = new ConcurrentHashMap<>();
  private final Map<String, AtomicInteger> attempts = new ConcurrentHashMap<>();
  private final Map<String, AtomicInteger> inFlight = new ConcurrentHashMap<>();
  private final Map<String, AtomicInteger> delivered = new ConcurrentHashMap<>();
  private final Map<String, AtomicInteger> accepted = new ConcurrentHashMap<>();
  private final Map<String, Answered> lastAnswer = new ConcurrentHashMap<>();
  private final Map<Fault, AtomicInteger> fired = new EnumMap<>(Fault.class);
  private final List<String> unexpected = new CopyOnWriteArrayList<>();
  private final AtomicInteger overlaps = new AtomicInteger();
  private final AtomicInteger requests = new AtomicInteger();
  private volatile CountDownLatch release = new CountDownLatch(1);

  ChaosFaultProxy(URI target) throws IOException {
    this.target = target;
    for (Fault fault : Fault.values()) {
      fired.put(fault, new AtomicInteger());
    }
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/internal/v1/oms-events", this::handle);
    executor =
        Executors.newCachedThreadPool(
            runnable -> {
              Thread thread = new Thread(runnable, "chaos-proxy");
              thread.setDaemon(true);
              return thread;
            });
    server.setExecutor(executor);
    server.start();
  }

  URI uri() {
    return URI.create(
        "http://127.0.0.1:" + server.getAddress().getPort() + "/internal/v1/oms-events");
  }

  /** Must be called before the event can be claimed, i.e. inside the appending transaction. */
  void plan(String eventId, List<Fault> faults) {
    plan.put(eventId, List.copyOf(faults));
  }

  void reset() {
    plan.clear();
    attempts.clear();
    inFlight.clear();
    delivered.clear();
    accepted.clear();
    lastAnswer.clear();
    unexpected.clear();
    fired.values().forEach(counter -> counter.set(0));
    overlaps.set(0);
    requests.set(0);
  }

  /** Lets held TIMEOUT_AFTER_FORWARD exchanges finish. Call only when no publisher is running. */
  void releaseHeld() {
    CountDownLatch previous = release;
    release = new CountDownLatch(1);
    previous.countDown();
  }

  int fired(Fault fault) {
    return fired.get(fault).get();
  }

  Map<String, Integer> firedByFault() {
    Map<String, Integer> out = new java.util.LinkedHashMap<>();
    for (Map.Entry<Fault, AtomicInteger> entry : fired.entrySet()) {
      out.put(entry.getKey().name().toLowerCase(java.util.Locale.ROOT), entry.getValue().get());
    }
    return out;
  }

  int requests() {
    return requests.get();
  }

  int overlaps() {
    return overlaps.get();
  }

  List<String> unexpected() {
    return new ArrayList<>(unexpected);
  }

  /** Deliveries the receiver answered with 2xx, per event id. */
  Map<String, Integer> delivered() {
    return snapshot(delivered);
  }

  /** Deliveries the receiver answered with 202 (first sight of the event id). */
  Map<String, Integer> accepted() {
    return snapshot(accepted);
  }

  Answered lastAnswer(String eventId) {
    return lastAnswer.get(eventId);
  }

  @Override
  public void close() {
    releaseHeld();
    server.stop(0);
    executor.shutdownNow();
  }

  private void handle(HttpExchange exchange) throws IOException {
    requests.incrementAndGet();
    byte[] raw = exchange.getRequestBody().readAllBytes();
    String eventId = exchange.getRequestHeaders().getFirst("X-Event-Id");
    if (eventId == null) {
      unexpected.add("request without X-Event-Id");
      exchange.sendResponseHeaders(400, -1);
      exchange.close();
      return;
    }
    // Step 1: Two requests for one event at the same time would mean two publishers hold it.
    AtomicInteger concurrent = inFlight.computeIfAbsent(eventId, ignored -> new AtomicInteger());
    if (concurrent.incrementAndGet() > 1) {
      overlaps.incrementAndGet();
    }
    boolean released = false;
    try {
      int attempt =
          attempts.computeIfAbsent(eventId, ignored -> new AtomicInteger()).incrementAndGet();
      Fault fault = faultFor(eventId, attempt);

      // Step 2: Faults answered here never reach the receiver.
      if (fault == Fault.RESET) {
        answer(eventId, fault, null);
        exchange.close();
        return;
      }
      if (fault == Fault.HTTP_500 || fault == Fault.HTTP_503) {
        answer(eventId, fault, null);
        exchange.sendResponseHeaders(fault == Fault.HTTP_500 ? 500 : 503, -1);
        exchange.close();
        return;
      }
      if (fault == Fault.HTTP_503_RETRY_AFTER || fault == Fault.HTTP_429_RETRY_AFTER) {
        int seconds =
            fault == Fault.HTTP_429_RETRY_AFTER ? RETRY_AFTER_429_SECONDS : RETRY_AFTER_503_SECONDS;
        answer(eventId, fault, seconds);
        exchange.getResponseHeaders().set("Retry-After", Integer.toString(seconds));
        exchange.sendResponseHeaders(fault == Fault.HTTP_429_RETRY_AFTER ? 429 : 503, -1);
        exchange.close();
        return;
      }

      // Step 3: Forward the raw bytes and the signature. The mock verifies HMAC and dedupes.
      HttpResponse<Void> response = forward(exchange, raw, eventId);
      int status = response.statusCode();
      if (status >= 200 && status < 300) {
        delivered.computeIfAbsent(eventId, ignored -> new AtomicInteger()).incrementAndGet();
        if (status == 202) {
          accepted.computeIfAbsent(eventId, ignored -> new AtomicInteger()).incrementAndGet();
        } else if (status != 200) {
          unexpected.add("receiver answered " + status + " for " + eventId);
        }
      } else {
        unexpected.add("receiver answered " + status + " for " + eventId);
      }

      // Step 4: The receiver has the event, but the publisher never sees the ack.
      if (fault == Fault.RESET_AFTER_FORWARD) {
        answer(eventId, fault, null);
        exchange.close();
        return;
      }
      if (fault == Fault.TIMEOUT_AFTER_FORWARD) {
        answer(eventId, fault, null);
        concurrent.decrementAndGet();
        released = true;
        hold();
        exchange.close();
        return;
      }
      exchange.sendResponseHeaders(status, -1);
      exchange.close();
    } catch (IOException | RuntimeException ex) {
      unexpected.add("proxy error " + ex.getClass().getSimpleName() + " for " + eventId);
      exchange.close();
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      exchange.close();
    } finally {
      if (!released) {
        concurrent.decrementAndGet();
      }
    }
  }

  private Fault faultFor(String eventId, int attempt) {
    List<Fault> faults = plan.get(eventId);
    if (faults == null || attempt > faults.size()) {
      return null;
    }
    return faults.get(attempt - 1);
  }

  private void answer(String eventId, Fault fault, Integer retryAfterSeconds) {
    fired.get(fault).incrementAndGet();
    lastAnswer.put(eventId, new Answered(fault, Instant.now(), retryAfterSeconds));
  }

  private HttpResponse<Void> forward(HttpExchange exchange, byte[] raw, String eventId)
      throws IOException, InterruptedException {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(target)
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/json")
            .header("X-Event-Id", eventId)
            .POST(HttpRequest.BodyPublishers.ofByteArray(raw));
    String signature = exchange.getRequestHeaders().getFirst("X-Signature");
    if (signature != null) {
      request.header("X-Signature", signature);
    }
    return client.send(request.build(), HttpResponse.BodyHandlers.discarding());
  }

  private void hold() throws InterruptedException {
    // Step 1: Wait for the test to release, not for a clock. The limit only stops a leak.
    if (!release.await(HOLD_LIMIT.toMillis(), TimeUnit.MILLISECONDS)) {
      unexpected.add("held exchange was never released");
    }
  }

  private static Map<String, Integer> snapshot(Map<String, AtomicInteger> source) {
    Map<String, Integer> out = new java.util.HashMap<>();
    source.forEach((key, value) -> out.put(key, value.get()));
    return out;
  }
}
