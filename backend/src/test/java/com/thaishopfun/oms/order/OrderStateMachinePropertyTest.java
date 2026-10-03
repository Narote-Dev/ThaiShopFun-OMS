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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
  void legalTransitionSequencesKeepModelAndHistoryInSync() {
    for (int repetition = 0; repetition < RUNS; repetition++) {
      runPropertySequence(repetition);
    }
  }

  private void runPropertySequence(int repetition) {
    Shop shop = shop();
    SalesOrder order = seedOrder(shop, "COD_PENDING", "UNFULFILLED", "NONE");
    Model model = new Model("COD_PENDING", "UNFULFILLED", "NONE");
    Random random = new Random(17_431L * repetition);
    GuardContext guards = new GuardContext(false, true, Instant.now());
    long version = order.version();
    int steps = 1 + random.nextInt(10);

    for (int i = 0; i < steps; i++) {
      Step step = randomStep(model, random);
      if (step == null) {
        break;
      }
      SalesOrder current = as(shop, () -> orders.findById(order.id()).orElseThrow());
      long historyBefore = historyCount(shop, order.id());
      try {
        SalesOrder updated =
            as(
                shop,
                () -> {
                  switch (step.dimension) {
                    case "PAYMENT" -> {
                      return stateMachine
                          .applyPaymentStatus(
                              current, step.to, "prop", "TEST", Instant.now(), guards)
                          .order();
                    }
                    case "FULFILLMENT" -> {
                      return stateMachine
                          .applyFulfillmentStatus(current, step.to, "prop", "TEST", guards)
                          .order();
                    }
                    case "HOLD" -> {
                      return stateMachine
                          .applyHoldReason(current, step.to, "prop-note", "prop", "TEST")
                          .order();
                    }
                    default -> throw new IllegalStateException(step.dimension);
                  }
                });
        model.apply(step);
        version++;
        assertThat(updated.version()).isEqualTo(version);
        assertThat(updated.paymentStatus()).isEqualTo(model.payment);
        assertThat(updated.fulfillmentStatus()).isEqualTo(model.fulfillment);
        assertThat(updated.holdReason()).isEqualTo(model.hold);
        assertThat(historyCount(shop, order.id())).isEqualTo(historyBefore + 1);
      } catch (OrderStateException ignored) {
        assertThat(historyCount(shop, order.id())).isEqualTo(historyBefore);
        SalesOrder unchanged = as(shop, () -> orders.findById(order.id()).orElseThrow());
        assertThat(unchanged.version()).isEqualTo(version);
      }
    }
  }

  private Step randomStep(Model model, Random random) {
    List<Step> options = new ArrayList<>();
    addEdges(options, "PAYMENT", model.payment);
    if ("NONE".equals(model.hold) && Set.of("PAID", "COD_PENDING").contains(model.payment)) {
      addEdges(options, "FULFILLMENT", model.fulfillment);
    }
    addEdges(options, "HOLD", model.hold);
    if (options.isEmpty()) {
      return null;
    }
    return options.get(random.nextInt(options.size()));
  }

  private void addEdges(List<Step> options, String dimension, String from) {
    Set<String> targets =
        OrderStateMachine.transitionTable()
            .getOrDefault(dimension, Map.of())
            .getOrDefault(from, Set.of());
    for (String to : targets) {
      if (!from.equals(to)) {
        options.add(new Step(dimension, to));
      }
    }
  }

  private record Step(String dimension, String to) {}

  private static final class Model {
    String payment;
    String fulfillment;
    String hold;

    Model(String payment, String fulfillment, String hold) {
      this.payment = payment;
      this.fulfillment = fulfillment;
      this.hold = hold;
    }

    void apply(Step step) {
      switch (step.dimension) {
        case "PAYMENT" -> payment = step.to;
        case "FULFILLMENT" -> fulfillment = step.to;
        case "HOLD" -> hold = step.to;
        default -> throw new IllegalStateException(step.dimension);
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

  private SalesOrder seedOrder(
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
