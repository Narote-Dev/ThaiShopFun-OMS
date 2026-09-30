package com.thaishopfun.oms.stock;

import static org.assertj.core.api.Assertions.assertThat;

import com.thaishopfun.oms.stock.StockFixture.Shop;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** No oversell and no deadlock under real concurrency. Invariants are checked after each run. */
class StockConcurrencyTest extends StockTestBase {

  private static final Logger log = LoggerFactory.getLogger(StockConcurrencyTest.class);

  @Test
  @Timeout(value = 2, unit = TimeUnit.MINUTES)
  void fiftyThreadsOnTenUnitsGiveExactlyTenReservations() throws Exception {
    Shop shop = fixture.shop("ACTIVE");
    UUID sku = fixture.sku(shop, 10);
    List<Callable<ReserveResult>> calls = new ArrayList<>();
    for (int i = 0; i < 50; i++) {
      calls.add(
          () ->
              as(
                  shop,
                  () ->
                      engine.reserve(
                          StockOwner.checkout("chk-" + UUID.randomUUID()),
                          List.of(ReserveItem.of(sku, 1)),
                          "key-" + UUID.randomUUID())));
    }

    List<ReserveResult> results = runTogether(calls, 50);

    // Step 1: Exactly 10 winners and 40 OUT_OF_STOCK. Stock, rows, and ledger all say 10.
    assertThat(results.stream().filter(ReserveResult::reserved)).hasSize(10);
    assertThat(results.stream().filter(result -> !result.reserved())).hasSize(40);
    assertThat(fixture.reserved(shop, sku)).isEqualTo(10);
    assertThat(fixture.reservations(shop, "ACTIVE")).isEqualTo(10);
    assertThat(fixture.ledger(shop, "RESERVE")).isEqualTo(10);
    fixture.assertInvariants(shop);
  }

  @Test
  @Timeout(value = 2, unit = TimeUnit.MINUTES)
  void bundlesCompetingWithTheirComponentNeverOversell() throws Exception {
    Shop shop = fixture.shop("ACTIVE");
    UUID a = fixture.sku(shop, 10);
    UUID b = fixture.sku(shop, 4);
    UUID pair = fixture.bundle(shop, Map.of(a, 2));
    UUID mixed = fixture.bundle(shop, Map.of(a, 1, b, 1));
    UUID[] kinds = {a, pair, mixed};
    List<Callable<ReserveResult>> calls = new ArrayList<>();
    for (int i = 0; i < 60; i++) {
      UUID item = kinds[i % kinds.length];
      calls.add(
          () ->
              as(
                  shop,
                  () ->
                      engine.reserve(
                          StockOwner.checkout("chk-" + UUID.randomUUID()),
                          List.of(ReserveItem.of(item, 1)),
                          "key-" + UUID.randomUUID())));
    }

    List<ReserveResult> results = runTogether(calls, 60);

    // Step 1: Held A = sum of winners' A lines, never above on_hand. Same for B.
    int heldA = heldFor(results, a);
    int heldB = heldFor(results, b);
    assertThat(heldA).isLessThanOrEqualTo(10).isEqualTo(fixture.reserved(shop, a));
    assertThat(heldB).isLessThanOrEqualTo(4).isEqualTo(fixture.reserved(shop, b));
    // Step 2: 20 single-A requests cannot all win, and a single-A only loses at 0 left.
    assertThat(heldA).isEqualTo(10);
    fixture.assertInvariants(shop);
  }

  @Test
  @Timeout(value = 4, unit = TimeUnit.MINUTES)
  void oppositeOrderPairsWithReleaseAndConsumeNeverDeadlock() throws Exception {
    Shop shop = fixture.shop("ACTIVE");
    UUID a = fixture.sku(shop, 100_000);
    UUID b = fixture.sku(shop, 100_000);
    double deadlocksBefore = counter(StockRetry.RETRY_METRIC, "deadlock");
    double serializationBefore = counter(StockRetry.RETRY_METRIC, "serialization");
    double busyBefore = busyTotal();
    List<Callable<Void>> calls = new ArrayList<>();
    for (int pair = 0; pair < 250; pair++) {
      int n = pair;
      // Step 1: {A, B} and {B, A}. Then release, consume, or keep, so every write path mixes in.
      for (List<UUID> order : List.of(List.of(a, b), List.of(b, a))) {
        calls.add(
            () -> {
              StockOwner owner = StockOwner.order("ord-" + UUID.randomUUID());
              List<ReserveItem> items =
                  order.stream().map(sku -> ReserveItem.of(sku, 1 + n % 3)).toList();
              ReserveResult held =
                  as(shop, () -> engine.reserve(owner, items, "key-" + UUID.randomUUID()));
              assertThat(held.reserved()).isTrue();
              switch (n % 3) {
                case 0 ->
                    as(
                        shop,
                        () ->
                            engine.release(held.reservationGroupId(), "key-" + UUID.randomUUID()));
                case 1 -> as(shop, () -> engine.consume(owner, "key-" + UUID.randomUUID()));
                default -> {}
              }
              return null;
            });
      }
    }

    // Step 2: Every call succeeds. No 40P01 or StockBusyException reaches a caller.
    runTogether(calls, 32);

    double deadlockRetries = counter(StockRetry.RETRY_METRIC, "deadlock") - deadlocksBefore;
    double serializationRetries =
        counter(StockRetry.RETRY_METRIC, "serialization") - serializationBefore;
    log.info(
        "deadlock pairs: {} calls, deadlock retries {}, serialization retries {}",
        calls.size(),
        deadlockRetries,
        serializationRetries);
    // Step 3: Id-ordered locking means the retry path is never needed here.
    assertThat(deadlockRetries).isZero();
    assertThat(busyTotal() - busyBefore).isZero();
    fixture.assertInvariants(shop);
  }

  private double busyTotal() {
    double total = 0;
    for (StockRetry.Cause cause : StockRetry.Cause.values()) {
      total += counter(StockRetry.BUSY_METRIC, cause.tag);
    }
    return total;
  }

  private static int heldFor(List<ReserveResult> results, UUID sku) {
    return results.stream()
        .filter(ReserveResult::reserved)
        .flatMap(result -> result.lines().stream())
        .filter(line -> line.skuId().equals(sku))
        .mapToInt(ReservedLine::qty)
        .sum();
  }

  /** Starts every call behind one latch and returns results in submission order. */
  private static <T> List<T> runTogether(List<Callable<T>> calls, int threads) throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    CountDownLatch go = new CountDownLatch(1);
    try {
      List<Future<T>> futures = new ArrayList<>();
      for (Callable<T> call : calls) {
        futures.add(
            pool.submit(
                () -> {
                  go.await();
                  return call.call();
                }));
      }
      go.countDown();
      List<T> results = new ArrayList<>();
      for (Future<T> future : futures) {
        results.add(future.get(3, TimeUnit.MINUTES));
      }
      return results;
    } finally {
      pool.shutdownNow();
    }
  }
}
