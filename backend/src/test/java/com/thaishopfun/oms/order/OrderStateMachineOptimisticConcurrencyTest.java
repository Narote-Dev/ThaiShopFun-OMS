package com.thaishopfun.oms.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.thaishopfun.oms.auth.AuthTestSupport;
import com.thaishopfun.oms.auth.UuidV7;
import com.thaishopfun.oms.order.OrderStateMachine.GuardContext;
import com.thaishopfun.oms.tenant.TenantContext;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@ActiveProfiles("test")
@SpringBootTest
class OrderStateMachineOptimisticConcurrencyTest {

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    AuthTestSupport.register(registry);
  }

  @Autowired OrderStateMachine stateMachine;
  @Autowired SalesOrderRepository orders;
  @Autowired JdbcTemplate jdbc;
  @Autowired PlatformTransactionManager transactionManager;

  @AfterEach
  void clearTenant() {
    TenantContext.clear();
  }

  @Test
  void conflictingTransitionsYieldOneWinnerAndOptimisticLockException() throws Exception {
    Shop shop = shop();
    SalesOrder order = seed(shop);
    GuardContext guards = new GuardContext(false, true, Instant.now());
    ExecutorService pool = Executors.newFixedThreadPool(2);
    CountDownLatch start = new CountDownLatch(1);
    AtomicReference<Throwable> failure = new AtomicReference<>();
    try {
      Future<?> payment =
          pool.submit(
              () -> {
                start.await(10, TimeUnit.SECONDS);
                try {
                  as(
                      shop,
                      () -> {
                        SalesOrder current = orders.findById(order.id()).orElseThrow();
                        stateMachine.applyPaymentStatus(
                            current, "PAID", "race", "TEST", Instant.now(), guards);
                        return null;
                      });
                } catch (Throwable ex) {
                  failure.compareAndSet(null, ex);
                }
                return null;
              });
      Future<?> hold =
          pool.submit(
              () -> {
                start.await(10, TimeUnit.SECONDS);
                try {
                  as(
                      shop,
                      () -> {
                        SalesOrder current = orders.findById(order.id()).orElseThrow();
                        stateMachine.applyHoldReason(current, "MANUAL", "race", "race", "TEST");
                        return null;
                      });
                } catch (Throwable ex) {
                  failure.compareAndSet(null, ex);
                }
                return null;
              });
      start.countDown();
      payment.get(30, TimeUnit.SECONDS);
      hold.get(30, TimeUnit.SECONDS);
    } finally {
      pool.shutdownNow();
    }
    assertThat(failure.get()).isInstanceOf(OrderOptimisticLockException.class);
    long history =
        as(
            shop,
            () ->
                jdbc.queryForObject(
                    "SELECT count(*) FROM order_status_history WHERE order_id = ?",
                    Long.class,
                    order.id()));
    assertThat(history).isEqualTo(1);
    SalesOrder finalOrder = as(shop, () -> orders.findById(order.id()).orElseThrow());
    assertThat(finalOrder.version()).isEqualTo(2L);
  }

  private SalesOrder seed(Shop shop) {
    SalesOrder order =
        new SalesOrder(
            UuidV7.generate(),
            shop.tenant(),
            shop.channelAccount(),
            "TSF-" + UuidV7.generate(),
            "ACTIVE",
            "PENDING",
            "UNFULFILLED",
            "NONE",
            null,
            null,
            "PREPAID",
            "THB",
            new BigDecimal("100.00"),
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            new BigDecimal("100.00"),
            Instant.now().truncatedTo(ChronoUnit.MICROS),
            null,
            null,
            1L,
            0);
    as(
        shop,
        () -> {
          orders.insert(order);
          return null;
        });
    return order;
  }

  private void as(Shop shop, Runnable call) {
    as(
        shop,
        () -> {
          call.run();
          return null;
        });
  }

  private <T> T as(Shop shop, Supplier<T> call) {
    TenantContext.set(shop.tenant(), null);
    try {
      return new TransactionTemplate(transactionManager).execute(status -> call.get());
    } finally {
      TenantContext.clear();
    }
  }

  private static Shop shop() {
    UUID tenant = UuidV7.generate();
    UUID channelAccount = UuidV7.generate();
    String tsfShopId = "shop-" + tenant;
    try (Connection admin = AuthTestSupport.admin()) {
      exec(
          admin,
          "INSERT INTO tenant (id, name, tsf_shop_id, membership_tier, entitlement_status, "
              + "ent_ver) VALUES (?, 'Shop', ?, 'PRO', 'ACTIVE', 1)",
          tenant,
          tsfShopId);
      exec(
          admin,
          "INSERT INTO channel_account (id, tenant_id, channel, external_shop_id, status) "
              + "VALUES (?, ?, 'TSF', ?, 'CONNECTED')",
          channelAccount,
          tenant,
          tsfShopId);
    } catch (SQLException ex) {
      throw new IllegalStateException(ex);
    }
    return new Shop(tenant, channelAccount);
  }

  private static void exec(Connection admin, String sql, Object... params) throws SQLException {
    try (PreparedStatement statement = admin.prepareStatement(sql)) {
      for (int i = 0; i < params.length; i++) {
        statement.setObject(i + 1, params[i]);
      }
      statement.executeUpdate();
    }
  }

  private record Shop(UUID tenant, UUID channelAccount) {}
}
