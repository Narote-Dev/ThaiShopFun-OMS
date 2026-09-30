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

  @Test
  @Timeout(value = 2, unit = TimeUnit.MINUTES)
  void sameOwnerWithTwentyKeysReservesOnce() throws Exception {
    Shop shop = fixture.shop("ACTIVE");
    UUID a = fixture.sku(shop, 1_000);
    UUID b = fixture.sku(shop, 1_000);
    StockOwner owner = StockOwner.checkout("chk-" + UUID.randomUUID());
    double busyBefore = busyTotal();
    List<Callable<Object>> calls = new ArrayList<>();
    for (int i = 0; i < 20; i++) {
      // Step 1: Disjoint SKU sets too, so only the owner lock (not inventory) serializes them.
      UUID sku = i % 2 == 0 ? a : b;
      calls.add(
          () -> {
            try {
              return as(
                  shop,
                  () ->
                      engine.reserve(
                          owner, List.of(ReserveItem.of(sku, 1)), "key-" + UUID.randomUUID()));
            } catch (StockOperationException ex) {
              return ex.error();
            }
          });
    }

    List<Object> results = runTogether(calls, 20);

    // Step 2: One winner, 19 OWNER_ALREADY_RESERVED, nothing busy, one ACTIVE row.
    assertThat(results.stream().filter(ReserveResult.class::isInstance)).hasSize(1);
    assertThat(results.stream().filter(StockError.OWNER_ALREADY_RESERVED::equals)).hasSize(19);
    assertThat(busyTotal() - busyBefore).isZero();
    assertThat(fixture.reservations(shop, "ACTIVE")).isEqualTo(1);
    assertThat(fixture.reserved(shop, a) + fixture.reserved(shop, b)).isEqualTo(1);
    fixture.assertInvariants(shop);
  }

  @Test
  @Timeout(value = 3, unit = TimeUnit.MINUTES)
  void transferRacingExpiryHasExactlyOneOutcome() throws Exception {
    Shop shop = fixture.shop("ACTIVE");
    UUID sku = fixture.sku(shop, 1_000);
    int transferred = 0;
    int expired = 0;
    for (int round = 0; round < 30; round++) {
      // Step 1: A 1 minute hold. The job runs at +2 min; the transfer sees the engine clock,
      // where the hold is still live. Whichever locks the inventory row first decides.
      ReserveResult held =
          as(
              shop,
              () ->
                  engine.reserve(
                      StockOwner.checkout("chk-" + UUID.randomUUID()),
                      List.of(ReserveItem.of(sku, 1)),
                      "key-" + UUID.randomUUID(),
                      java.time.Duration.ofMinutes(1)));
      java.time.Instant jobNow = clock.instant().plus(java.time.Duration.ofMinutes(2));
      String orderRef = "ord-" + UUID.randomUUID();
      List<Callable<Object>> race =
          List.of(
              () -> {
                try {
                  return as(
                      shop,
                      () ->
                          engine.transferOwner(
                              held.reservationGroupId(), orderRef, "key-" + UUID.randomUUID()));
                } catch (StockOperationException ex) {
                  return ex.error();
                }
              },
              () -> expiryJob.runOnce(jobNow));
      List<Object> results = runTogether(race, 2);

      // Step 2: Exactly one outcome for the group.
      Map<String, Object> row = fixture.groupRows(shop, held.reservationGroupId()).get(0);
      if (results.get(0) instanceof TransferResult) {
        transferred++;
        assertThat(row.get("status")).as("round %s", round).isEqualTo("ACTIVE");
        assertThat(row.get("owner_type")).isEqualTo("ORDER");
        assertThat(row.get("expires_at")).isNull();
      } else {
        expired++;
        assertThat(results.get(0))
            .as("round %s", round)
            .isEqualTo(StockError.RESERVATION_NOT_ACTIVE);
        assertThat(row.get("status")).isEqualTo("EXPIRED");
        assertThat(row.get("owner_type")).isEqualTo("CHECKOUT");
      }
    }
    log.info("transfer vs expiry: {} transferred, {} expired", transferred, expired);
    // Step 3: One RELEASE per expired group, ORDER holds still reserved, books balance.
    assertThat(transferred + expired).isEqualTo(30);
    assertThat(fixture.ledger(shop, "RELEASE")).isEqualTo(expired);
    assertThat(fixture.reserved(shop, sku)).isEqualTo(transferred);
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
