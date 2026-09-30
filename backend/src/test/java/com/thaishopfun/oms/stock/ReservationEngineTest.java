package com.thaishopfun.oms.stock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.thaishopfun.oms.stock.StockFixture.Shop;
import com.thaishopfun.oms.stock.StockTestConfig.Fault;
import com.thaishopfun.oms.tenant.TenantContext;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Reserve, transfer, release, consume, unpack, idempotency, events, and availability. */
class ReservationEngineTest extends StockTestBase {

  private static String key() {
    return "key-" + UUID.randomUUID();
  }

  private static StockOwner checkout() {
    return StockOwner.checkout("chk-" + UUID.randomUUID());
  }

  private static StockOwner order() {
    return StockOwner.order("ord-" + UUID.randomUUID());
  }

  @Test
  void reserveExplodesBundlesAndSumsPerComponent() {
    Shop shop = fixture.shop("ACTIVE");
    UUID a = fixture.sku(shop, 10);
    UUID b = fixture.sku(shop, 10);
    UUID bundle = fixture.bundle(shop, Map.of(a, 2, b, 1));
    StockOwner owner = checkout();

    // Step 1: 2 bundles (4 A + 2 B) plus 1 A. One row per component, one group.
    ReserveResult result =
        as(
            shop,
            () ->
                engine.reserve(
                    owner, List.of(ReserveItem.of(bundle, 2), ReserveItem.of(a, 1)), key()));
    assertThat(result.reserved()).isTrue();
    assertThat(result.lines())
        .extracting(ReservedLine::skuId, ReservedLine::qty)
        .containsExactlyInAnyOrder(
            org.assertj.core.groups.Tuple.tuple(a, 5), org.assertj.core.groups.Tuple.tuple(b, 2));
    assertThat(result.expiresAt()).isEqualTo(clock.instant().plus(Duration.ofMinutes(15)));

    // Step 2: Inventory, rows, and ledger match.
    assertThat(fixture.reserved(shop, a)).isEqualTo(5);
    assertThat(fixture.reserved(shop, b)).isEqualTo(2);
    assertThat(fixture.groupRows(shop, result.reservationGroupId())).hasSize(2);
    assertThat(fixture.ledger(shop, "RESERVE")).isEqualTo(2);
    fixture.assertInvariants(shop);
  }

  @Test
  void bundleShortOneComponentReservesNothing() {
    Shop shop = fixture.shop("ACTIVE");
    UUID a = fixture.sku(shop, 10);
    UUID b = fixture.sku(shop, 0);
    UUID noRow = fixture.skuWithoutStock(shop);
    UUID bundle = fixture.bundle(shop, Map.of(a, 1, b, 1));
    UUID bundleNoRow = fixture.bundle(shop, Map.of(a, 1, noRow, 1));

    // Step 1: B is short. Nothing is held, and the short component is listed.
    ReserveResult result =
        as(shop, () -> engine.reserve(checkout(), List.of(ReserveItem.of(bundle, 1)), key()));
    assertThat(result.status()).isEqualTo(ReserveResult.Status.OUT_OF_STOCK);
    assertThat(result.shortfalls())
        .singleElement()
        .satisfies(
            shortfall -> {
              assertThat(shortfall.skuId()).isEqualTo(b);
              assertThat(shortfall.requested()).isEqualTo(1);
              assertThat(shortfall.available()).isZero();
              assertThat(shortfall.requestedBy()).containsExactly(bundle);
            });

    // Step 2: A component with no inventory row counts as 0 available.
    ReserveResult missing =
        as(shop, () -> engine.reserve(checkout(), List.of(ReserveItem.of(bundleNoRow, 1)), key()));
    assertThat(missing.shortfalls()).extracting(Shortfall::skuId).containsExactly(noRow);

    assertThat(fixture.reserved(shop, a)).isZero();
    assertThat(fixture.reservations(shop, "ACTIVE")).isZero();
    assertThat(fixture.ledger(shop, "RESERVE")).isZero();
    assertThat(events.forTenant(shop.tenant())).isEmpty();
    fixture.assertInvariants(shop);
  }

  @Test
  void transferKeepsReservedAndLedgerAndClearsExpiry() {
    Shop shop = fixture.shop("ACTIVE");
    UUID a = fixture.sku(shop, 10);
    ReserveResult held =
        as(shop, () -> engine.reserve(checkout(), List.of(ReserveItem.of(a, 3)), key()));
    long ledgerBefore = fixture.ledgerRows(shop);
    int eventsBefore = events.forTenant(shop.tenant()).size();
    String orderRef = "ord-" + UUID.randomUUID();
    String transferKey = key();

    // Step 1: Owner becomes ORDER, expires_at is cleared, reserved and ledger do not move.
    TransferResult moved =
        as(shop, () -> engine.transferOwner(held.reservationGroupId(), orderRef, transferKey));
    assertThat(moved.owner()).isEqualTo(StockOwner.order(orderRef));
    Map<String, Object> row = fixture.groupRows(shop, held.reservationGroupId()).get(0);
    assertThat(row.get("owner_type")).isEqualTo("ORDER");
    assertThat(row.get("owner_ref")).isEqualTo(orderRef);
    assertThat(row.get("expires_at")).isNull();
    assertThat(fixture.reserved(shop, a)).isEqualTo(3);
    assertThat(fixture.ledgerRows(shop)).isEqualTo(ledgerBefore);
    assertThat(events.forTenant(shop.tenant())).hasSize(eventsBefore);

    // Step 2: Replay and a second transfer to the same order are both no-ops.
    assertThat(
            as(shop, () -> engine.transferOwner(held.reservationGroupId(), orderRef, transferKey)))
        .isEqualTo(moved);
    assertThat(as(shop, () -> engine.transferOwner(held.reservationGroupId(), orderRef, key())))
        .isEqualTo(moved);

    // Step 3: Another order cannot take it.
    assertError(
        () -> as(shop, () -> engine.transferOwner(held.reservationGroupId(), "ord-other", key())),
        StockError.RESERVATION_NOT_ACTIVE);
    fixture.assertInvariants(shop);
  }

  @Test
  void transferOfExpiredOrReleasedGroupIsNotActive() {
    Shop shop = fixture.shop("ACTIVE");
    UUID a = fixture.sku(shop, 10);
    ReserveResult held =
        as(
            shop,
            () ->
                engine.reserve(
                    checkout(), List.of(ReserveItem.of(a, 2)), key(), Duration.ofMinutes(1)));

    // Step 1: Past expires_at but not swept. Transfer refuses and returns the stock right away.
    clock.advance(Duration.ofMinutes(2));
    assertError(
        () -> as(shop, () -> engine.transferOwner(held.reservationGroupId(), "ord-1", key())),
        StockError.RESERVATION_NOT_ACTIVE);
    assertThat(fixture.groupRows(shop, held.reservationGroupId()).get(0).get("status"))
        .isEqualTo("EXPIRED");
    assertThat(fixture.reserved(shop, a)).isZero();
    assertThat(fixture.ledger(shop, "RELEASE")).isEqualTo(1);

    // Step 2: A released group, and an unknown one.
    ReserveResult other =
        as(shop, () -> engine.reserve(checkout(), List.of(ReserveItem.of(a, 1)), key()));
    as(shop, () -> engine.release(other.reservationGroupId(), key()));
    assertError(
        () -> as(shop, () -> engine.transferOwner(other.reservationGroupId(), "ord-2", key())),
        StockError.RESERVATION_NOT_ACTIVE);
    assertError(
        () -> as(shop, () -> engine.transferOwner(UUID.randomUUID(), "ord-3", key())),
        StockError.RESERVATION_NOT_FOUND);
    fixture.assertInvariants(shop);
  }

  @Test
  void releaseReturnsStockAndIsIdempotentByState() {
    Shop shop = fixture.shop("ACTIVE");
    UUID a = fixture.sku(shop, 10);
    ReserveResult held =
        as(shop, () -> engine.reserve(checkout(), List.of(ReserveItem.of(a, 3)), key()));
    String releaseKey = key();

    // Step 1: RELEASED, reserved -= 3, ledger RELEASE (0, -3).
    ReservationChange first = as(shop, () -> engine.release(held.reservationGroupId(), releaseKey));
    assertThat(first.lines()).extracting(ReservedLine::qty).containsExactly(3);
    assertThat(fixture.reserved(shop, a)).isZero();
    assertThat(fixture.onHand(shop, a)).isEqualTo(10);
    assertThat(ledgerDeltas(shop, "RELEASE")).containsExactly(List.of(0, -3));

    // Step 2: Same key replays the first answer. A new key finds nothing ACTIVE and succeeds.
    assertThat(as(shop, () -> engine.release(held.reservationGroupId(), releaseKey)))
        .isEqualTo(first);
    assertThat(as(shop, () -> engine.release(held.reservationGroupId(), key())).lines()).isEmpty();
    assertThat(fixture.ledger(shop, "RELEASE")).isEqualTo(1);

    // Step 3: Release by owner works too.
    StockOwner owner = checkout();
    as(shop, () -> engine.reserve(owner, List.of(ReserveItem.of(a, 2)), key()));
    assertThat(as(shop, () -> engine.release(owner, key())).lines()).hasSize(1);
    assertThat(fixture.reserved(shop, a)).isZero();
    fixture.assertInvariants(shop);
  }

  @Test
  void consumeShipsAndUnpackReleases() {
    Shop shop = fixture.shop("ACTIVE");
    UUID a = fixture.sku(shop, 10);
    StockOwner shipped = order();
    StockOwner cancelled = order();
    as(shop, () -> engine.reserve(shipped, List.of(ReserveItem.of(a, 3)), key()));
    as(shop, () -> engine.reserve(cancelled, List.of(ReserveItem.of(a, 2)), key()));
    assertThat(fixture.reserved(shop, a)).isEqualTo(5);

    // Step 1: Consume: CONSUMED, on_hand and reserved both -3, ledger SHIP (-3, -3).
    ReservationChange ship = as(shop, () -> engine.consume(shipped, key()));
    assertThat(ship.lines()).extracting(ReservedLine::qty).containsExactly(3);
    assertThat(fixture.onHand(shop, a)).isEqualTo(7);
    assertThat(fixture.reserved(shop, a)).isEqualTo(2);
    assertThat(ledgerDeltas(shop, "SHIP")).containsExactly(List.of(-3, -3));
    assertError(
        () -> as(shop, () -> engine.consume(shipped, key())), StockError.RESERVATION_NOT_ACTIVE);

    // Step 2: Unpack: RELEASED with ledger UNPACK (0, -2).
    ReservationChange unpacked = as(shop, () -> engine.unpack(cancelled, key()));
    assertThat(unpacked.lines()).hasSize(1);
    assertThat(fixture.reserved(shop, a)).isZero();
    assertThat(fixture.onHand(shop, a)).isEqualTo(7);
    assertThat(ledgerDeltas(shop, "UNPACK")).containsExactly(List.of(0, -2));
    assertThat(fixture.reservations(shop, "CONSUMED")).isEqualTo(1);
    assertThat(fixture.reservations(shop, "RELEASED")).isEqualTo(1);

    // Step 3: Both only apply to ORDER owners.
    assertThatThrownBy(() -> as(shop, () -> engine.unpack(checkout(), key())))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> as(shop, () -> engine.consume(checkout(), key())))
        .isInstanceOf(IllegalArgumentException.class);
    fixture.assertInvariants(shop);
  }

  @Test
  void ownerWithActiveRowsCannotReserveAgainWithAnotherKey() {
    Shop shop = fixture.shop("ACTIVE");
    UUID a = fixture.sku(shop, 10);
    UUID b = fixture.sku(shop, 10);
    StockOwner owner = checkout();
    as(shop, () -> engine.reserve(owner, List.of(ReserveItem.of(a, 1)), key()));

    // Step 1: A different SKU does not help. The failure is stored under the new key.
    String second = key();
    assertError(
        () -> as(shop, () -> engine.reserve(owner, List.of(ReserveItem.of(b, 1)), second)),
        StockError.OWNER_ALREADY_RESERVED);
    as(shop, () -> engine.release(owner, key()));
    assertError(
        () -> as(shop, () -> engine.reserve(owner, List.of(ReserveItem.of(b, 1)), second)),
        StockError.OWNER_ALREADY_RESERVED);

    // Step 2: After release, a fresh key reserves again.
    assertThat(
            as(shop, () -> engine.reserve(owner, List.of(ReserveItem.of(b, 1)), key())).reserved())
        .isTrue();
    fixture.assertInvariants(shop);
  }

  @Test
  void missingDefaultWarehouseAndUnknownSkuAreStoredFailures() {
    Shop shop = fixture.shop("ACTIVE", false);
    UUID a = fixture.sku(shop, 10);

    assertError(
        () -> as(shop, () -> engine.reserve(checkout(), List.of(ReserveItem.of(a, 1)), key())),
        StockError.NO_DEFAULT_WAREHOUSE);
    ReserveResult explicit =
        as(
            shop,
            () ->
                engine.reserve(
                    checkout(), List.of(new ReserveItem(a, 1, shop.warehouse())), key()));
    assertThat(explicit.reserved()).isTrue();
    assertError(
        () ->
            as(
                shop,
                () ->
                    engine.reserve(
                        checkout(),
                        List.of(new ReserveItem(UUID.randomUUID(), 1, shop.warehouse())),
                        key())),
        StockError.UNKNOWN_SKU);
  }

  @Test
  void idempotentReplayConflictAndOutOfStockReplay() {
    Shop shop = fixture.shop("ACTIVE");
    UUID a = fixture.sku(shop, 5);
    StockOwner owner = checkout();
    String k = key();

    // Step 1: Same key, same request: one effect, same answer.
    ReserveResult first = as(shop, () -> engine.reserve(owner, List.of(ReserveItem.of(a, 2)), k));
    ReserveResult again = as(shop, () -> engine.reserve(owner, List.of(ReserveItem.of(a, 2)), k));
    assertThat(again).isEqualTo(first);
    assertThat(fixture.reserved(shop, a)).isEqualTo(2);
    assertThat(fixture.ledger(shop, "RESERVE")).isEqualTo(1);

    // Step 2: Same key, different request: conflict, no effect.
    assertThatThrownBy(
            () -> as(shop, () -> engine.reserve(owner, List.of(ReserveItem.of(a, 3)), k)))
        .isInstanceOf(IdempotencyConflictException.class);
    assertThat(fixture.reserved(shop, a)).isEqualTo(2);

    // Step 3: OUT_OF_STOCK is stored. Stock added later does not change the replay.
    StockOwner late = checkout();
    String oosKey = key();
    ReserveResult oos =
        as(shop, () -> engine.reserve(late, List.of(ReserveItem.of(a, 10)), oosKey));
    assertThat(oos.status()).isEqualTo(ReserveResult.Status.OUT_OF_STOCK);
    fixture.receive(shop, a, 100);
    assertThat(as(shop, () -> engine.reserve(late, List.of(ReserveItem.of(a, 10)), oosKey)))
        .isEqualTo(oos);
    assertThat(fixture.reserved(shop, a)).isEqualTo(2);
    fixture.assertInvariants(shop);
  }

  @Test
  void concurrentSameKeyHasOneEffect() throws Exception {
    Shop shop = fixture.shop("ACTIVE");
    UUID a = fixture.sku(shop, 100);
    StockOwner owner = checkout();
    String k = key();
    int threads = 8;
    CyclicBarrier start = new CyclicBarrier(threads);
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    try {
      List<Future<ReserveResult>> futures = new ArrayList<>();
      for (int i = 0; i < threads; i++) {
        futures.add(
            pool.submit(
                () -> {
                  start.await(10, TimeUnit.SECONDS);
                  return as(shop, () -> engine.reserve(owner, List.of(ReserveItem.of(a, 4)), k));
                }));
      }
      // Step 1: Every caller gets the same group; stock is held once.
      Set<ReserveResult> results = new java.util.HashSet<>();
      for (Future<ReserveResult> future : futures) {
        results.add(future.get(60, TimeUnit.SECONDS));
      }
      assertThat(results).hasSize(1);
      assertThat(results.iterator().next().reserved()).isTrue();
    } finally {
      pool.shutdownNow();
    }
    assertThat(fixture.reserved(shop, a)).isEqualTo(4);
    assertThat(fixture.reservations(shop, "ACTIVE")).isEqualTo(1);
    assertThat(fixture.ledger(shop, "RESERVE")).isEqualTo(1);
    fixture.assertInvariants(shop);
  }

  @Test
  void repeatableReadCallerFailsFastAndJoinedTransactionRollsBackWithCaller() {
    Shop shop = fixture.shop("ACTIVE");
    UUID a = fixture.sku(shop, 10);

    // Step 1: REPEATABLE READ caller: refused before anything is written.
    TransactionTemplate repeatable = new TransactionTemplate(transactions);
    repeatable.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    TenantContext.set(shop.tenant(), null);
    try {
      assertThatThrownBy(
              () ->
                  repeatable.executeWithoutResult(
                      status -> engine.reserve(checkout(), List.of(ReserveItem.of(a, 1)), key())))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining(StockTransactions.READ_COMMITTED_REQUIRED);

      // Step 2: READ COMMITTED caller: the engine joins, and the caller's rollback undoes it.
      TransactionTemplate readCommitted = new TransactionTemplate(transactions);
      readCommitted.executeWithoutResult(
          status -> {
            assertThat(engine.reserve(checkout(), List.of(ReserveItem.of(a, 2)), key()).reserved())
                .isTrue();
            status.setRollbackOnly();
          });
    } finally {
      TenantContext.clear();
    }
    assertThat(fixture.reserved(shop, a)).isZero();
    assertThat(fixture.reservations(shop, "ACTIVE")).isZero();
    // Step 3: Nothing was published for the rolled-back transaction.
    assertThat(events.forTenant(shop.tenant())).isEmpty();
  }

  @Test
  void stockChangedAfterCommitIncludesEveryBundleUsingTheComponent() {
    Shop shop = fixture.shop("ACTIVE");
    UUID a = fixture.sku(shop, 10);
    UUID b = fixture.sku(shop, 10);
    UUID bundleA = fixture.bundle(shop, Map.of(a, 2));
    UUID bundleAb = fixture.bundle(shop, Map.of(a, 1, b, 1));
    UUID bundleB = fixture.bundle(shop, Map.of(b, 1));

    // Step 1: Changing A alone names A plus both bundles that use A, not the B-only bundle.
    ReserveResult held =
        as(shop, () -> engine.reserve(checkout(), List.of(ReserveItem.of(a, 1)), key()));
    List<StockChanged> published = events.forTenant(shop.tenant());
    assertThat(published).hasSize(1);
    assertThat(published.get(0).componentSkuIds()).containsExactly(a);
    assertThat(published.get(0).bundleSkuIds()).containsExactlyInAnyOrder(bundleA, bundleAb);
    assertThat(published.get(0).allSkuIds()).doesNotContain(bundleB);

    // Step 2: Release publishes again. A failed transaction publishes nothing.
    as(shop, () -> engine.release(held.reservationGroupId(), key()));
    assertThat(events.forTenant(shop.tenant())).hasSize(2);
    faults.failNext(Fault.THROW);
    assertThatThrownBy(
            () -> as(shop, () -> engine.reserve(checkout(), List.of(ReserveItem.of(b, 1)), key())))
        .hasMessageContaining("injected failure");
    assertThat(events.forTenant(shop.tenant())).hasSize(2);
    assertThat(fixture.reserved(shop, b)).isZero();
  }

  @Test
  void injectedSerializationFailureIsRetriedWhole() {
    Shop shop = fixture.shop("ACTIVE");
    UUID a = fixture.sku(shop, 10);
    double before = counter(StockRetry.RETRY_METRIC, "serialization");
    String k = key();
    StockOwner owner = checkout();

    // Step 1: A real 40001 after the lock. The retry reruns everything, including the key.
    faults.failNext(Fault.SERIALIZATION);
    ReserveResult result = as(shop, () -> engine.reserve(owner, List.of(ReserveItem.of(a, 1)), k));
    assertThat(result.reserved()).isTrue();
    assertThat(counter(StockRetry.RETRY_METRIC, "serialization")).isEqualTo(before + 1);
    assertThat(fixture.reserved(shop, a)).isEqualTo(1);
    assertThat(fixture.ledger(shop, "RESERVE")).isEqualTo(1);

    // Step 2: A plain exception is not retried and leaves no key behind: the same key works.
    faults.failNext(Fault.THROW);
    String k2 = key();
    StockOwner other = checkout();
    assertThatThrownBy(
            () -> as(shop, () -> engine.reserve(other, List.of(ReserveItem.of(a, 1)), k2)))
        .isInstanceOf(IllegalStateException.class);
    assertThat(as(shop, () -> engine.reserve(other, List.of(ReserveItem.of(a, 1)), k2)).reserved())
        .isTrue();
    fixture.assertInvariants(shop);
  }

  @Test
  void availabilityForSkusBundlesAndListings() {
    Shop shop = fixture.shop("ACTIVE");
    UUID a = fixture.sku(shop, 10);
    UUID b = fixture.sku(shop, 5);
    UUID noRow = fixture.skuWithoutStock(shop);
    UUID bundle = fixture.bundle(shop, Map.of(a, 2, b, 1));
    UUID bundleNoRow = fixture.bundle(shop, Map.of(a, 1, noRow, 1));
    as(shop, () -> engine.reserve(order(), List.of(ReserveItem.of(a, 3)), key()));

    // Step 1: physical_available = on_hand - reserved; bundle = min(floor(avail / qty)).
    Map<UUID, Integer> values =
        as(shop, () -> availability.available(List.of(a, b, noRow, bundle, bundleNoRow), null));
    assertThat(values)
        .containsEntry(a, 7)
        .containsEntry(b, 5)
        .containsEntry(noRow, 0)
        .containsEntry(bundle, 3)
        .containsEntry(bundleNoRow, 0);

    // Step 2: channel_exposed = max(0, available - safety_buffer).
    UUID listing = fixture.listing(shop, a, 2);
    UUID bigBuffer = fixture.listing(shop, a, 50);
    UUID bundleListing = fixture.listing(shop, bundle, 1);
    UUID unmapped = fixture.listing(shop, null, 0);
    assertThat(as(shop, () -> availability.channelExposed(listing))).isEqualTo(5);
    assertThat(as(shop, () -> availability.channelExposed(bigBuffer))).isZero();
    assertThat(as(shop, () -> availability.channelExposed(bundleListing))).isEqualTo(2);
    assertThat(as(shop, () -> availability.channelExposed(unmapped))).isZero();
    assertError(
        () -> as(shop, () -> availability.channelExposed(UUID.randomUUID())),
        StockError.UNKNOWN_LISTING);
  }

  @Test
  void joinedCallerGetsOneEngineWritePerTransaction() {
    Shop shop = fixture.shop("ACTIVE");
    UUID a = fixture.sku(shop, 10);
    TransactionTemplate caller = new TransactionTemplate(transactions);
    TenantContext.set(shop.tenant(), null);
    try {
      // Step 1: The first write joins; the second in the same transaction is refused.
      assertThatThrownBy(
              () ->
                  caller.executeWithoutResult(
                      status -> {
                        assertThat(
                                engine
                                    .reserve(checkout(), List.of(ReserveItem.of(a, 1)), key())
                                    .reserved())
                            .isTrue();
                        engine.reserve(checkout(), List.of(ReserveItem.of(a, 1)), key());
                      }))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("only one engine write per caller transaction");

      // Step 2: The refusal rolled the caller back. A new caller transaction gets its own write,
      // and reads do not count against it.
      caller.executeWithoutResult(
          status -> {
            assertThat(availability.available(a, null)).isEqualTo(10);
            assertThat(engine.reserve(checkout(), List.of(ReserveItem.of(a, 2)), key()).reserved())
                .isTrue();
          });
    } finally {
      TenantContext.clear();
    }
    assertThat(fixture.reserved(shop, a)).isEqualTo(2);
    assertThat(fixture.reservations(shop, "ACTIVE")).isEqualTo(1);
    fixture.assertInvariants(shop);
  }

  private List<List<Integer>> ledgerDeltas(Shop shop, String reason) {
    return fixture.inTenant(
        shop.tenant(),
        () ->
            jdbc.query(
                "SELECT delta_on_hand, delta_reserved FROM inventory_ledger WHERE reason = ? "
                    + "ORDER BY created_at, id",
                (rs, row) -> List.of(rs.getInt(1), rs.getInt(2)),
                reason));
  }

  private static void assertError(Runnable call, StockError error) {
    assertThatThrownBy(call::run)
        .isInstanceOf(StockOperationException.class)
        .extracting(ex -> ((StockOperationException) ex).error())
        .isEqualTo(error);
  }
}
