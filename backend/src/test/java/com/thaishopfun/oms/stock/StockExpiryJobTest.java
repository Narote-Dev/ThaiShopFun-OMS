package com.thaishopfun.oms.stock;

import static org.assertj.core.api.Assertions.assertThat;

import com.thaishopfun.oms.stock.StockFixture.Shop;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** The expiry job with a fixed clock, across entitlement states and concurrent runs. */
class StockExpiryJobTest extends StockTestBase {

  private ReserveResult hold(Shop shop, UUID sku, int qty) {
    return as(
        shop,
        () ->
            engine.reserve(
                StockOwner.checkout("chk-" + UUID.randomUUID()),
                List.of(ReserveItem.of(sku, qty)),
                "key-" + UUID.randomUUID()));
  }

  @Test
  void holdsPastExpiryAreReturnedByTheNextRun() {
    assertThat(properties.getExpiry().getIntervalMs()).isEqualTo(60_000);
    Shop shop = fixture.shop("ACTIVE");
    UUID sku = fixture.sku(shop, 10);
    ReserveResult checkout = hold(shop, sku, 3);
    as(
        shop,
        () ->
            engine.reserve(
                StockOwner.order("ord-" + UUID.randomUUID()),
                List.of(ReserveItem.of(sku, 2)),
                "key-" + UUID.randomUUID()));

    // Step 1: One second before expires_at nothing moves.
    clock.advance(properties.getCheckoutTtl().minusSeconds(1));
    expiryJob.runOnce();
    assertThat(fixture.reserved(shop, sku)).isEqualTo(5);
    int eventsBefore = events.forTenant(shop.tenant()).size();

    // Step 2: The next 60 s tick (1 minute later) expires the checkout hold, not the order.
    clock.advance(Duration.ofMillis(properties.getExpiry().getIntervalMs()));
    expiryJob.runOnce();
    assertThat(fixture.groupRows(shop, checkout.reservationGroupId()).get(0).get("status"))
        .isEqualTo("EXPIRED");
    assertThat(fixture.reserved(shop, sku)).isEqualTo(2);
    assertThat(fixture.reservations(shop, "ACTIVE")).isEqualTo(1);
    assertThat(fixture.ledger(shop, "RELEASE")).isEqualTo(1);
    assertThat(events.forTenant(shop.tenant())).hasSize(eventsBefore + 1);
    fixture.assertInvariants(shop);
  }

  @Test
  void graceAndSuspendedShopsGetTheirStockBack() {
    List<Shop> shops =
        List.of(fixture.shop("ACTIVE"), fixture.shop("GRACE"), fixture.shop("SUSPENDED"));
    List<UUID> skus = shops.stream().map(shop -> fixture.sku(shop, 5)).toList();
    for (int i = 0; i < shops.size(); i++) {
      hold(shops.get(i), skus.get(i), 4);
    }

    clock.advance(properties.getCheckoutTtl().plusSeconds(1));
    expiryJob.runOnce();

    for (int i = 0; i < shops.size(); i++) {
      assertThat(fixture.reserved(shops.get(i), skus.get(i))).as("shop %s", i).isZero();
      assertThat(fixture.reservations(shops.get(i), "EXPIRED")).isEqualTo(1);
      fixture.assertInvariants(shops.get(i));
    }
  }

  @Test
  void twoConcurrentRunsDoNotDoubleRelease() throws Exception {
    Shop shop = fixture.shop("SUSPENDED");
    List<UUID> skus =
        List.of(fixture.sku(shop, 100), fixture.sku(shop, 100), fixture.sku(shop, 100));
    for (int i = 0; i < 60; i++) {
      hold(shop, skus.get(i % skus.size()), 1);
    }
    clock.advance(properties.getCheckoutTtl().plusSeconds(1));

    // Step 1: Two job runs at the same instant, on two threads.
    ExecutorService pool = Executors.newFixedThreadPool(2);
    CountDownLatch go = new CountDownLatch(1);
    try {
      List<Future<Integer>> runs =
          List.of(
              pool.submit(
                  () -> {
                    go.await();
                    return expiryJob.runOnce();
                  }),
              pool.submit(
                  () -> {
                    go.await();
                    return expiryJob.runOnce();
                  }));
      go.countDown();
      for (Future<Integer> run : runs) {
        run.get(2, TimeUnit.MINUTES);
      }
    } finally {
      pool.shutdownNow();
    }

    // Step 2: Each row expired once: 60 RELEASE rows, reserved back to 0, invariants hold.
    assertThat(fixture.reservations(shop, "EXPIRED")).isEqualTo(60);
    assertThat(fixture.ledger(shop, "RELEASE")).isEqualTo(60);
    for (UUID sku : skus) {
      assertThat(fixture.reserved(shop, sku)).isZero();
    }
    fixture.assertInvariants(shop);
  }
}
