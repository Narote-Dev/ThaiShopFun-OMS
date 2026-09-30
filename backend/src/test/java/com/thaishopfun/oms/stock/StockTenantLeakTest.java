package com.thaishopfun.oms.stock;

import static org.assertj.core.api.Assertions.assertThat;

import com.thaishopfun.oms.auth.AuthTestSupport;
import com.thaishopfun.oms.stock.StockFixture.Shop;
import com.thaishopfun.oms.stock.StockTestConfig.Fault;
import com.thaishopfun.oms.stock.StockTestConfig.FaultHooks;
import com.thaishopfun.oms.stock.StockTestConfig.MutableClock;
import com.thaishopfun.oms.tenant.TenantContext;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * The T03 pool-leak pattern for the engine: one pooled connection, ~1,000 operations alternating
 * two tenants, a failure every 7th call (a mid-transaction exception or a real 40001 that forces a
 * retry), and the expiry job switching tenants in between. Each tenant only ever sees its own rows,
 * and {@code app.tenant_id} is blank on the connection after every call.
 */
@ActiveProfiles("test")
@SpringBootTest(
    properties = {
      "spring.datasource.hikari.maximum-pool-size=1",
      "spring.datasource.hikari.minimum-idle=1",
      "spring.datasource.hikari.connection-timeout=5000"
    })
@Import(StockTestConfig.class)
// Closed after the class: every cached context holds a pool on the shared Postgres.
@DirtiesContext
class StockTenantLeakTest {

  private static final int ITERATIONS = 1000;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    AuthTestSupport.register(registry);
  }

  @Autowired ReservationEngine engine;
  @Autowired StockAvailability availability;
  @Autowired StockExpiryJob expiryJob;
  @Autowired JdbcTemplate jdbc;
  @Autowired PlatformTransactionManager transactions;
  @Autowired MutableClock clock;
  @Autowired FaultHooks faults;

  @Test
  @Timeout(value = 4, unit = TimeUnit.MINUTES)
  void poolOfOneNeverLeaksTenantAcrossEngineCalls() {
    StockFixture fixture = new StockFixture(jdbc, transactions);
    Shop a = fixture.shop("ACTIVE");
    Shop b = fixture.shop("SUSPENDED");
    Map<UUID, UUID> skuOf =
        Map.of(a.tenant(), fixture.sku(a, 100_000), b.tenant(), fixture.sku(b, 100_000));
    Map<UUID, StockOwner> lastCheckout = new HashMap<>();
    int injected = 0;
    int firedBefore = faults.fired();

    for (int i = 0; i < ITERATIONS; i++) {
      Shop shop = i % 2 == 0 ? a : b;
      UUID sku = skuOf.get(shop.tenant());
      // Step 1: Arm a fault every 7th call, alternating a plain throw and a real 40001.
      if (i % 7 == 0) {
        faults.failNext(i % 14 == 0 ? Fault.THROW : Fault.SERIALIZATION);
      }
      try {
        Set<UUID> touched = new HashSet<>();
        switch ((i / 2) % 5) {
          case 0 -> {
            StockOwner owner = StockOwner.checkout("chk-" + UUID.randomUUID());
            ReserveResult held =
                as(
                    shop,
                    () ->
                        engine.reserve(
                            owner, List.of(ReserveItem.of(sku, 1)), key(), Duration.ofMinutes(1)));
            held.lines().forEach(line -> touched.add(line.skuId()));
            lastCheckout.put(shop.tenant(), owner);
          }
          case 1 -> {
            StockOwner owner = lastCheckout.remove(shop.tenant());
            if (owner != null) {
              as(shop, () -> engine.release(owner, key()))
                  .lines()
                  .forEach(line -> touched.add(line.skuId()));
            }
          }
          case 2 -> {
            StockOwner owner = StockOwner.order("ord-" + UUID.randomUUID());
            as(shop, () -> engine.reserve(owner, List.of(ReserveItem.of(sku, 2)), key()));
            as(shop, () -> engine.consume(owner, key()))
                .lines()
                .forEach(line -> touched.add(line.skuId()));
          }
          case 3 -> assertThat(as(shop, () -> availability.available(sku, null))).isPositive();
          default -> {
            // Step 2: The job switches tenants on this same connection, per transaction.
            clock.advance(Duration.ofMinutes(2));
            expiryJob.runOnce();
          }
        }
        assertThat(touched).as("iteration %s", i).isSubsetOf(Set.of(sku));
      } catch (IllegalStateException ex) {
        assertThat(ex).as("iteration %s", i).hasMessageContaining("injected failure");
        injected++;
      } finally {
        faults.reset();
      }

      // Step 3: No context on the thread and a blank setting on the only connection.
      assertThat(TenantContext.tenantId()).as("iteration %s", i).isNull();
      String setting =
          jdbc.queryForObject("SELECT current_setting('app.tenant_id', true)", String.class);
      assertThat(setting == null ? "" : setting).as("iteration %s", i).isEmpty();
      assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_reservation", Long.class))
          .as("no context sees nothing")
          .isZero();

      // Step 4: Inside a tenant transaction, only that tenant's stock rows are visible.
      Set<UUID> visible =
          new HashSet<>(
              fixture.inTenant(
                  shop.tenant(),
                  () ->
                      jdbc.queryForList(
                          "SELECT tenant_id FROM inventory UNION SELECT tenant_id FROM "
                              + "stock_reservation UNION SELECT tenant_id FROM inventory_ledger",
                          UUID.class)));
      assertThat(visible).as("iteration %s", i).containsExactly(shop.tenant());
    }

    // Step 5: Faults really fired (throws and forced retries), and the books still balance.
    assertThat(faults.fired() - firedBefore).isGreaterThan(50);
    assertThat(injected).isPositive();
    fixture.assertInvariants(a);
    fixture.assertInvariants(b);
  }

  private static String key() {
    return "key-" + UUID.randomUUID();
  }

  private static <T> T as(Shop shop, Supplier<T> call) {
    TenantContext.set(shop.tenant(), null);
    try {
      return call.get();
    } finally {
      TenantContext.clear();
    }
  }
}
