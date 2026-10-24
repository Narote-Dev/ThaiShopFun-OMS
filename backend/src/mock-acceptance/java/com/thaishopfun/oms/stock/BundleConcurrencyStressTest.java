package com.thaishopfun.oms.stock;

import static org.assertj.core.api.Assertions.assertThat;

import com.thaishopfun.oms.invariant.InvariantChecker;
import com.thaishopfun.oms.invariant.VerifyInvariants;
import com.thaishopfun.oms.stock.StockFixture.Shop;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;

/** Plan-scale additions to StockConcurrencyTest; all writes use the real engine as oms_app. */
@VerifyInvariants(scope = VerifyInvariants.Scope.STOCK_ONLY)
@Timeout(value = 180, unit = TimeUnit.SECONDS)
class BundleConcurrencyStressTest extends StockTestBase {
  @Autowired InvariantChecker checker;
  private double deadlocksBefore;

  @BeforeEach
  void snapshotDeadlocks() {
    deadlocksBefore = deadlocks();
  }

  @AfterEach
  void zeroDeadlocksAtEngineBoundary() {
    // RETRY counts recovered 40P01; BUSY counts the final exhausted attempt too.
    assertThat(deadlocks() - deadlocksBefore).as("engine SQLSTATE 40P01 occurrences").isZero();
  }

  @Test
  void bundleVersusComponentRaceUntilStockRunsOut() throws Exception {
    Shop shop = fixture.shop("ACTIVE");
    UUID a = fixture.sku(shop, 101);
    UUID bundle = fixture.bundle(shop, Map.of(a, 2));
    AtomicInteger successes = new AtomicInteger();
    AtomicInteger units = new AtomicInteger();
    race(
        () -> sellUntilEmpty(shop, bundle, 2, successes, units),
        () -> sellUntilEmpty(shop, a, 1, successes, units));
    assertThat(units.get()).isEqualTo(101);
    assertThat(successes.get()).isLessThanOrEqualTo(101);
    assertThat(fixture.onHand(shop, a)).isZero();
    assertThat(fixture.reserved(shop, a)).isZero();
    assertThat(as(shop, () -> availability.available(bundle, null))).isZero();
    fixture.assertInvariants(shop, checker);
  }

  @Test
  void twoBundlesSharingAComponentReserveConcurrently() throws Exception {
    Shop shop = fixture.shop("ACTIVE");
    UUID a = fixture.sku(shop, 60);
    UUID b = fixture.sku(shop, 60);
    UUID c = fixture.sku(shop, 60);
    UUID first = fixture.bundle(shop, Map.of(a, 2, b, 1));
    UUID second = fixture.bundle(shop, Map.of(a, 1, c, 3));
    AtomicInteger firstWins = new AtomicInteger();
    AtomicInteger secondWins = new AtomicInteger();
    race(
        () -> reserveUntilEmpty(shop, first, firstWins),
        () -> reserveUntilEmpty(shop, second, secondWins));
    assertThat(2 * firstWins.get() + secondWins.get()).isEqualTo(60);
    assertThat(fixture.reserved(shop, a)).isEqualTo(60);
    assertThat(fixture.reserved(shop, b)).isEqualTo(firstWins.get());
    assertThat(fixture.reserved(shop, c)).isEqualTo(3 * secondWins.get());
    fixture.assertInvariants(shop, checker);
  }

  @Test
  void cancellingABundleReturnsEveryComponentInFull() {
    Shop shop = fixture.shop("ACTIVE");
    UUID a = fixture.sku(shop, 17);
    UUID b = fixture.sku(shop, 23);
    UUID bundle = fixture.bundle(shop, Map.of(a, 2, b, 3));
    StockOwner owner = StockOwner.order("cancel-bundle");
    ReserveResult held = reserve(shop, owner, bundle, 4, "cancel-reserve");
    assertThat(held.reserved()).isTrue();
    assertThat(fixture.reserved(shop, a)).isEqualTo(8);
    assertThat(fixture.reserved(shop, b)).isEqualTo(12);
    as(shop, () -> engine.release(owner, "cancel"));
    assertReturned(shop, a, b, bundle, 17, 23, 7);
    assertThat(fixture.reservations(shop, "RELEASED")).isEqualTo(2);
    assertReleaseLedger(shop, Map.of(a, -8, b, -12));
    fixture.assertInvariants(shop, checker);
  }

  @Test
  void expiringABundleReturnsComponentsAndRecomputesAvailability() {
    Shop shop = fixture.shop("ACTIVE");
    UUID a = fixture.sku(shop, 17);
    UUID b = fixture.sku(shop, 23);
    UUID bundle = fixture.bundle(shop, Map.of(a, 2, b, 3));
    ReserveResult held = reserve(shop, StockOwner.checkout("expire-bundle"), bundle, 4, "hold");
    assertThat(held.reserved()).isTrue();
    assertThat(as(shop, () -> availability.available(bundle, null))).isEqualTo(3);
    clock.advance(properties.getCheckoutTtl().minusSeconds(1));
    expiryJob.runOnce();
    assertThat(fixture.reservations(shop, "ACTIVE")).isEqualTo(2);
    assertThat(fixture.ledger(shop, "RELEASE")).isZero();
    clock.advance(Duration.ofSeconds(1));
    expiryJob.runOnce();
    assertThat(fixture.reservations(shop, "EXPIRED")).isEqualTo(2);
    assertReturned(shop, a, b, bundle, 17, 23, 7);
    assertReleaseLedger(shop, Map.of(a, -8, b, -12));
    expiryJob.runOnce();
    assertThat(fixture.ledger(shop, "RELEASE")).isEqualTo(2);
    fixture.assertInvariants(shop, checker);
  }

  @Test
  void oneThousandConcurrentOppositeOrderPairsHaveZeroDeadlocks() throws Exception {
    Shop shop = fixture.shop("ACTIVE");
    UUID a = fixture.sku(shop, 10_000);
    UUID b = fixture.sku(shop, 10_000);
    AtomicInteger consumed = new AtomicInteger();
    List<Callable<Void>> workers = new ArrayList<>();
    // Eight independent pairs, each synchronized before every one of its 125 reserves.
    // Both members always have a worker; no barrier can be stranded behind queued tasks.
    for (int pair = 0; pair < 8; pair++) {
      CyclicBarrier barrier = new CyclicBarrier(2);
      int pairId = pair;
      for (int side = 0; side < 2; side++) {
        int sideId = side;
        List<ReserveItem> items =
            (side == 0 ? List.of(a, b) : List.of(b, a))
                .stream().map(sku -> ReserveItem.of(sku, 1)).toList();
        workers.add(
            () -> {
              for (int round = 0; round < 125; round++) {
                String ref = "pair-" + pairId + "-" + sideId + "-" + round;
                StockOwner owner = StockOwner.order(ref);
                barrier.await(30, TimeUnit.SECONDS);
                ReserveResult held = as(shop, () -> engine.reserve(owner, items, ref));
                assertThat(held.reserved()).as(ref).isTrue();
                if (round % 2 == 0) {
                  as(shop, () -> engine.release(owner, ref + "-release"));
                } else {
                  as(shop, () -> engine.consume(owner, ref + "-consume"));
                  consumed.incrementAndGet();
                }
              }
              return null;
            });
      }
    }
    run(workers);
    assertThat(consumed.get()).isEqualTo(992);
    for (UUID sku : List.of(a, b)) {
      assertThat(fixture.onHand(shop, sku)).isEqualTo(10_000 - consumed.get());
      assertThat(fixture.reserved(shop, sku)).isZero();
    }
    assertThat(fixture.reservations(shop, "ACTIVE")).isZero();
    assertThat(fixture.ledger(shop, "RESERVE")).isEqualTo(4_000);
    fixture.assertInvariants(shop, checker);
  }

  private Void sellUntilEmpty(
      Shop shop, UUID sku, int qty, AtomicInteger wins, AtomicInteger units) {
    for (int i = 0; i < 102; i++) {
      StockOwner owner = StockOwner.order("sell-" + sku + "-" + i);
      if (!reserve(shop, owner, sku, 1, "sell-" + sku + "-" + i).reserved()) return null;
      wins.incrementAndGet();
      units.addAndGet(qty);
      as(shop, () -> engine.consume(owner, "ship-" + owner.ref()));
    }
    throw new AssertionError("stock never exhausted");
  }

  private Void reserveUntilEmpty(Shop shop, UUID sku, AtomicInteger wins) {
    for (int i = 0; i < 61; i++) {
      if (!reserve(
              shop, StockOwner.order("shared-" + sku + "-" + i), sku, 1, "shared-" + sku + "-" + i)
          .reserved()) return null;
      wins.incrementAndGet();
    }
    throw new AssertionError("stock never exhausted");
  }

  private ReserveResult reserve(Shop shop, StockOwner owner, UUID sku, int qty, String key) {
    return as(shop, () -> engine.reserve(owner, List.of(ReserveItem.of(sku, qty)), key));
  }

  private void assertReturned(
      Shop shop, UUID a, UUID b, UUID bundle, int handA, int handB, int available) {
    assertThat(fixture.onHand(shop, a)).isEqualTo(handA);
    assertThat(fixture.onHand(shop, b)).isEqualTo(handB);
    assertThat(fixture.reserved(shop, a)).isZero();
    assertThat(fixture.reserved(shop, b)).isZero();
    assertThat(as(shop, () -> availability.available(bundle, null))).isEqualTo(available);
  }

  private void assertReleaseLedger(Shop shop, Map<UUID, Integer> expected) {
    fixture.inTenant(
        shop.tenant(),
        () -> {
          var rows =
              jdbc.queryForList(
                  "SELECT sku_id, delta_on_hand, delta_reserved FROM inventory_ledger WHERE reason = 'RELEASE'");
          assertThat(rows).hasSize(expected.size());
          for (var row : rows) {
            assertThat(row.get("delta_on_hand")).isEqualTo(0);
            assertThat(row.get("delta_reserved")).isEqualTo(expected.get(row.get("sku_id")));
          }
          return null;
        });
  }

  private double deadlocks() {
    return counter(StockRetry.RETRY_METRIC, "deadlock")
        + counter(StockRetry.BUSY_METRIC, "deadlock");
  }

  private static void race(Callable<Void> first, Callable<Void> second) throws Exception {
    CyclicBarrier start = new CyclicBarrier(2);
    run(
        List.of(
            () -> {
              start.await(30, TimeUnit.SECONDS);
              return first.call();
            },
            () -> {
              start.await(30, TimeUnit.SECONDS);
              return second.call();
            }));
  }

  private static void run(List<Callable<Void>> workers) throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(workers.size());
    try {
      List<Future<Void>> futures = new ArrayList<>();
      for (Callable<Void> worker : workers) futures.add(pool.submit(worker));
      for (Future<Void> future : futures) future.get(150, TimeUnit.SECONDS);
    } finally {
      pool.shutdownNow();
      assertThat(pool.awaitTermination(10, TimeUnit.SECONDS))
          .as("workers stopped before invariant checks")
          .isTrue();
    }
  }
}
