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
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
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
class OrderStateMachinePropertyTest {

  private static final int RUNS = 200;
  private static final Instant NOW = Instant.parse("2026-10-03T12:00:00Z");

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    AuthTestSupport.register(registry);
  }

  @Autowired OrderStateMachine stateMachine;
  @Autowired SalesOrderRepository orders;
  @Autowired OrderStatusHistoryRepository history;
  @Autowired JdbcTemplate jdbc;
  @Autowired PlatformTransactionManager transactionManager;

  @AfterEach
  void clearTenant() {
    TenantContext.clear();
  }

  @Test
  void randomTransitionsMatchExpectedMatrixModel() {
    for (int repetition = 0; repetition < RUNS; repetition++) {
      runPropertySequence(repetition);
    }
  }

  private void runPropertySequence(int repetition) {
    Shop shop = shop();
    SalesOrder order = seedOrder(shop);
    Model model = new Model("ACTIVE", "COD_PENDING", "UNFULFILLED", "NONE");
    Random random = new Random(17_431L * repetition);
    GuardContext guards = new GuardContext(false, true, NOW, false);
    long version = order.version();

    int steps = 1 + random.nextInt(12);
    for (int i = 0; i < steps; i++) {
      String dimension = pickDimension(random);
      String from = model.value(dimension);
      String to =
          OrderStateMachine.allowedValues(dimension)
              .get(random.nextInt(OrderStateMachine.allowedValues(dimension).size()));
      boolean legal =
          oracleExpectsSuccess(
              model.orderStatus,
              model.payment,
              model.fulfillment,
              model.hold,
              dimension,
              from,
              to,
              guards,
              order.id(),
              shop);

      SalesOrder current = as(shop, () -> orders.findById(order.id()).orElseThrow());
      long versionBefore = current.version();
      long historyBefore = historyCount(shop, order.id());
      try {
        as(
            shop,
            () -> {
              applyStep(stateMachine, current, dimension, to, guards);
              return null;
            });
        if (!legal) {
          throw new AssertionError(
              "expected rejection for " + dimension + " " + from + " -> " + to);
        }
        model.apply(dimension, to);
        SalesOrder updated = as(shop, () -> orders.findById(order.id()).orElseThrow());
        if (!from.equals(to)) {
          assertThat(updated.version()).isEqualTo(versionBefore + 1);
        } else {
          assertThat(updated.version()).isEqualTo(versionBefore);
        }
        version = updated.version();
        assertThat(updated.orderStatus()).isEqualTo(model.orderStatus);
        assertThat(updated.paymentStatus()).isEqualTo(model.payment);
        assertThat(updated.fulfillmentStatus()).isEqualTo(model.fulfillment);
        assertThat(updated.holdReason()).isEqualTo(model.hold);
        if (!from.equals(to)) {
          assertThat(historyCount(shop, order.id())).isEqualTo(historyBefore + 1);
          assertLastHistory(shop, order.id(), dimension, from, to);
        } else {
          assertThat(historyCount(shop, order.id())).isEqualTo(historyBefore);
        }
      } catch (OrderStateException ex) {
        if (legal) {
          throw new AssertionError("unexpected rejection: " + ex.getMessage(), ex);
        }
        assertThat(historyCount(shop, order.id())).isEqualTo(historyBefore);
        SalesOrder unchanged = as(shop, () -> orders.findById(order.id()).orElseThrow());
        assertThat(unchanged.version()).isEqualTo(version);
      }
    }
  }

  private boolean oracleExpectsSuccess(
      String orderStatus,
      String payment,
      String fulfillment,
      String hold,
      String dimension,
      String from,
      String to,
      GuardContext guards,
      UUID orderId,
      Shop shop) {
    if (!from.equals(modelValue(dimension, orderStatus, payment, fulfillment, hold))) {
      return false;
    }
    if (!OrderStateMachineExpectedTransitions.isLegalEdge(dimension, from, to)) {
      return false;
    }
    if (from.equals(to) && !"HOLD".equals(dimension)) {
      if ("FULFILLMENT".equals(dimension)
          && ("CANCELLED".equals(orderStatus) || "COMPLETED".equals(orderStatus))) {
        return false;
      }
      return true;
    }
    if ("ORDER".equals(dimension) && "CANCELLED".equals(orderStatus) && !"CANCELLED".equals(to)) {
      return false;
    }
    if ("ORDER".equals(dimension)
        && "CANCELLED".equals(to)
        && Set.of("SHIPPED", "DELIVERED").contains(fulfillment)) {
      return false;
    }
    if ("ORDER".equals(dimension) && "COMPLETED".equals(to)) {
      if (!"DELIVERED".equals(fulfillment)) {
        return false;
      }
      Optional<Instant> delivered =
          as(shop, () -> history.transitionedAt(orderId, "FULFILLMENT", "DELIVERED"));
      if (delivered.isEmpty() || delivered.get().isAfter(NOW.minus(7, ChronoUnit.DAYS))) {
        return false;
      }
    }
    if (("FULFILLMENT".equals(dimension) || "HOLD".equals(dimension))
        && ("CANCELLED".equals(orderStatus) || "COMPLETED".equals(orderStatus))) {
      return false;
    }
    if ("FULFILLMENT".equals(dimension) && !"NONE".equals(hold) && !from.equals(to)) {
      return false;
    }
    if ("FULFILLMENT".equals(dimension) && "READY_TO_PICK".equals(to)) {
      if (!"ACTIVE".equals(orderStatus)) {
        return false;
      }
      if (!Set.of("PAID", "COD_PENDING").contains(payment)) {
        return false;
      }
      if (guards.stockEnforced() && !guards.reservationCoversMappedLines()) {
        return false;
      }
    }
    return true;
  }

  private static String modelValue(
      String dimension, String orderStatus, String payment, String fulfillment, String hold) {
    return switch (dimension) {
      case "ORDER" -> orderStatus;
      case "PAYMENT" -> payment;
      case "FULFILLMENT" -> fulfillment;
      case "HOLD" -> hold;
      default -> throw new IllegalArgumentException(dimension);
    };
  }

  private static String pickDimension(Random random) {
    String[] dimensions = {"ORDER", "PAYMENT", "FULFILLMENT", "HOLD"};
    return dimensions[random.nextInt(dimensions.length)];
  }

  private static void applyStep(
      OrderStateMachine machine,
      SalesOrder order,
      String dimension,
      String to,
      GuardContext guards) {
    switch (dimension) {
      case "ORDER" -> machine.applyOrderStatus(order, to, "prop", "TEST", guards);
      case "PAYMENT" -> machine.applyPaymentStatus(order, to, "prop", "TEST", NOW, guards);
      case "FULFILLMENT" -> machine.applyFulfillmentStatus(order, to, "prop", "TEST", guards);
      case "HOLD" -> {
        String note = to.equals(order.holdReason()) ? order.holdNote() : "prop-note";
        machine.applyHoldReason(order, to, note, "prop", "TEST");
      }
      default -> throw new IllegalStateException(dimension);
    }
  }

  private void assertLastHistory(
      Shop shop, UUID orderId, String dimension, String from, String to) {
    Map<String, Object> row =
        as(
            shop,
            () ->
                jdbc.queryForMap(
                    """
                    SELECT dimension, from_value, to_value
                    FROM order_status_history
                    WHERE order_id = ?
                    ORDER BY created_at DESC
                    LIMIT 1
                    """,
                    orderId));
    assertThat(row.get("dimension")).isEqualTo(dimension);
    assertThat(row.get("from_value")).isEqualTo(from);
    assertThat(row.get("to_value")).isEqualTo(to);
  }

  private static final class Model {
    String orderStatus;
    String payment;
    String fulfillment;
    String hold;

    Model(String orderStatus, String payment, String fulfillment, String hold) {
      this.orderStatus = orderStatus;
      this.payment = payment;
      this.fulfillment = fulfillment;
      this.hold = hold;
    }

    String value(String dimension) {
      return modelValue(dimension, orderStatus, payment, fulfillment, hold);
    }

    void apply(String dimension, String to) {
      switch (dimension) {
        case "ORDER" -> orderStatus = to;
        case "PAYMENT" -> payment = to;
        case "FULFILLMENT" -> fulfillment = to;
        case "HOLD" -> hold = to;
        default -> throw new IllegalStateException(dimension);
      }
    }
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

  private SalesOrder seedOrder(Shop shop) {
    SalesOrder order =
        new SalesOrder(
            UuidV7.generate(),
            shop.tenant(),
            shop.channelAccount(),
            "TSF-" + UuidV7.generate(),
            "ACTIVE",
            "COD_PENDING",
            "UNFULFILLED",
            "NONE",
            null,
            null,
            "COD",
            "THB",
            new BigDecimal("100.00"),
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            new BigDecimal("100.00"),
            Instant.now().truncatedTo(ChronoUnit.MICROS),
            null,
            null,
            null,
            1L);
    as(
        shop,
        () -> {
          orders.insert(order);
          return null;
        });
    return order;
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
