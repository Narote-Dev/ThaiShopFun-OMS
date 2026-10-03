package com.thaishopfun.oms.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
class OrderStateMachineTest {

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
  void readyToPickWhilePaymentPendingFails() {
    Shop shop = shop();
    SalesOrder order = seed(shop, "PENDING", "UNFULFILLED", "NONE");
    GuardContext guards = new GuardContext(true, true, Instant.now());

    assertThatThrownBy(
            () ->
                as(
                    shop,
                    () ->
                        stateMachine.applyFulfillmentStatus(
                            order, "READY_TO_PICK", "test", "TEST", guards)))
        .isInstanceOf(OrderStateException.class)
        .hasMessageContaining("PAID or COD_PENDING");

    SalesOrder unchanged = as(shop, () -> orders.findById(order.id()).orElseThrow());
    assertThat(unchanged.fulfillmentStatus()).isEqualTo("UNFULFILLED");
    assertThat(historyCount(shop, order.id())).isZero();
  }

  @Test
  void holdBlocksFulfillmentChanges() {
    Shop shop = shop();
    SalesOrder order = seed(shop, "COD_PENDING", "UNFULFILLED", "SKU_NOT_MAPPED");
    GuardContext guards = new GuardContext(false, false, Instant.now());

    assertThatThrownBy(
            () ->
                as(
                    shop,
                    () ->
                        stateMachine.applyFulfillmentStatus(
                            order, "READY_TO_PICK", "test", "TEST", guards)))
        .isInstanceOf(OrderStateException.class)
        .hasMessageContaining("hold blocks");
  }

  @Test
  void cancelAfterShippedIsRefused() {
    Shop shop = shop();
    SalesOrder order = seed(shop, "PAID", "SHIPPED", "NONE");
    GuardContext guards = new GuardContext(true, true, Instant.now());

    assertThatThrownBy(
            () ->
                as(
                    shop,
                    () ->
                        stateMachine.applyOrderStatus(order, "CANCELLED", "test", "TEST", guards)))
        .isInstanceOf(OrderStateException.class)
        .hasMessageContaining("cannot cancel after shipped");
    assertThat(historyCount(shop, order.id())).isZero();
  }

  @Test
  void forbiddenTransitionThrowsBeforeHistoryRow() {
    Shop shop = shop();
    SalesOrder order = seed(shop, "PAID", "UNFULFILLED", "NONE");
    GuardContext guards = new GuardContext(true, true, Instant.now());

    assertThatThrownBy(
            () ->
                as(
                    shop,
                    () ->
                        stateMachine.applyPaymentStatus(
                            order, "NOT_A_STATUS", "test", "TEST", null, guards)))
        .isInstanceOf(OrderStateException.class)
        .hasMessageContaining("forbidden PAYMENT");
    assertThat(historyCount(shop, order.id())).isZero();
  }

  @Test
  void observeModeReadyToPickWithoutReservationCoverage() {
    Shop shop = shop();
    SalesOrder order = seed(shop, "COD_PENDING", "UNFULFILLED", "NONE");
    GuardContext guards = new GuardContext(false, false, Instant.now());

    // Step 1: OBSERVE / DISCONNECTED modes do not require ORDER reservation coverage.
    SalesOrder updated =
        as(
            shop,
            () ->
                stateMachine
                    .applyFulfillmentStatus(order, "READY_TO_PICK", "test", "TEST", guards)
                    .order());
    assertThat(updated.fulfillmentStatus()).isEqualTo("READY_TO_PICK");
    assertThat(historyCount(shop, order.id())).isEqualTo(1);
  }

  @Test
  void illegalPaymentTransitionThrowsBeforeHistoryRow() {
    Shop shop = shop();
    SalesOrder order = seed(shop, "REFUNDED", "UNFULFILLED", "NONE");
    GuardContext guards = new GuardContext(true, true, Instant.now());

    assertThatThrownBy(
            () ->
                as(
                    shop,
                    () ->
                        stateMachine.applyPaymentStatus(
                            order, "PENDING", "test", "TEST", null, guards)))
        .isInstanceOf(OrderStateException.class)
        .hasMessageContaining("illegal PAYMENT");
    assertThat(historyCount(shop, order.id())).isZero();
  }

  @Test
  void cancelledOrderAllowsPaymentRefundTransitions() {
    Shop shop = shop();
    SalesOrder order =
        new SalesOrder(
            UuidV7.generate(),
            shop.tenant(),
            shop.channelAccount(),
            "TSF-" + UuidV7.generate(),
            "CANCELLED",
            "PAID",
            "DELIVERED",
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
            Instant.now(),
            null,
            1L,
            0);
    as(
        shop,
        () -> {
          orders.insert(order);
          return null;
        });
    GuardContext guards = new GuardContext(true, true, Instant.now());
    as(
        shop,
        () -> {
          stateMachine.applyPaymentStatus(
              orders.findById(order.id()).orElseThrow(),
              "REFUNDED",
              "refund",
              "TEST",
              Instant.now(),
              guards);
          return null;
        });
    SalesOrder updated = as(shop, () -> orders.findById(order.id()).orElseThrow());
    assertThat(updated.paymentStatus()).isEqualTo("REFUNDED");
  }

  @Test
  void cancelledOrderRejectsPaymentChange() {
    Shop shop = shop();
    SalesOrder order =
        new SalesOrder(
            UuidV7.generate(),
            shop.tenant(),
            shop.channelAccount(),
            "TSF-" + UuidV7.generate(),
            "CANCELLED",
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
    GuardContext guards = new GuardContext(true, true, Instant.now());

    assertThatThrownBy(
            () ->
                as(
                    shop,
                    () ->
                        stateMachine.applyPaymentStatus(
                            order, "PAID", "test", "TEST", Instant.now(), guards)))
        .isInstanceOf(OrderStateException.class)
        .hasMessageContaining("immutable");
  }

  @Test
  void transitionTableIsExposedForMatrixTests() {
    assertThat(OrderStateMachine.transitionTable())
        .containsKeys("ORDER", "PAYMENT", "FULFILLMENT", "HOLD");
    assertThat(OrderStateMachine.transitionTable().get("ORDER").get("ACTIVE"))
        .containsExactlyInAnyOrder("CANCELLED", "COMPLETED");
  }

  @Test
  void eachAppliedTransitionWritesExactlyOneHistoryRow() {
    Shop shop = shop();
    SalesOrder order = seed(shop, "PENDING", "UNFULFILLED", "NONE");
    GuardContext guards = new GuardContext(true, true, Instant.now());
    Instant paidAt = Instant.now().truncatedTo(ChronoUnit.MICROS);

    // Step 1: Payment transition appends one history row.
    SalesOrder paid =
        as(
            shop,
            () ->
                stateMachine
                    .applyPaymentStatus(order, "PAID", "paid", "TEST", paidAt, guards)
                    .order());
    assertThat(historyCount(shop, paid.id())).isEqualTo(1);

    // Step 2: Fulfillment transition appends a second row.
    SalesOrder ready =
        as(
            shop,
            () ->
                stateMachine
                    .applyFulfillmentStatus(paid, "READY_TO_PICK", "pick", "TEST", guards)
                    .order());
    assertThat(ready.fulfillmentStatus()).isEqualTo("READY_TO_PICK");
    assertThat(historyCount(shop, ready.id())).isEqualTo(2);

    // Step 3: Idempotent re-apply does not append.
    as(
        shop,
        () -> {
          stateMachine.applyFulfillmentStatus(ready, "READY_TO_PICK", "pick", "TEST", guards);
          return null;
        });
    assertThat(historyCount(shop, ready.id())).isEqualTo(2);
  }

  private long historyCount(Shop shop, UUID orderId) {
    return as(
        shop,
        () ->
            jdbc.queryForObject(
                "SELECT count(*) FROM order_status_history WHERE order_id = ?",
                Long.class,
                orderId));
  }

  private SalesOrder seed(
      Shop shop, String paymentStatus, String fulfillmentStatus, String holdReason) {
    SalesOrder order =
        new SalesOrder(
            UuidV7.generate(),
            shop.tenant(),
            shop.channelAccount(),
            "TSF-" + UuidV7.generate(),
            "ACTIVE",
            paymentStatus,
            fulfillmentStatus,
            holdReason,
            null,
            null,
            "COD",
            "THB",
            new BigDecimal("100.00"),
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            new BigDecimal("100.00"),
            Instant.now().truncatedTo(ChronoUnit.MICROS),
            "PAID".equals(paymentStatus) ? Instant.now().truncatedTo(ChronoUnit.MICROS) : null,
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

  private static void exec(Connection connection, String sql, Object... params)
      throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      for (int i = 0; i < params.length; i++) {
        statement.setObject(i + 1, params[i]);
      }
      statement.executeUpdate();
    }
  }

  private record Shop(UUID tenant, UUID channelAccount) {}
}
