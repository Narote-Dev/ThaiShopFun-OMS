package com.thaishopfun.oms.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.thaishopfun.oms.auth.AuthTestSupport;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@ActiveProfiles("test")
@SpringBootTest
@Testcontainers
class FlywayV13OrderHoldRetryTest {

  static final Set<String> V13_TABLES = Set.of("order_hold_retry");

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    AuthTestSupport.register(registry);
  }

  @Container
  static PostgreSQLContainer postgres =
      new PostgreSQLContainer("postgres:16-alpine").withInitScript("db/test-oms-app-login.sql");

  @Test
  void flywayHistoryIncludesVersion13() throws Exception {
    try (Connection admin = AuthTestSupport.admin();
        Statement statement = admin.createStatement();
        ResultSet history =
            statement.executeQuery(
                "SELECT version, success FROM flyway_schema_history WHERE version = '13'")) {
      assertThat(history.next()).isTrue();
      assertThat(history.getBoolean("success")).isTrue();
    }
  }

  @Test
  void orderHoldRetryIsForceRlsAndRejectsCrossTenantInsert() throws SQLException {
    UUID tenantA = UUID.randomUUID();
    UUID tenantB = UUID.randomUUID();
    UUID orderA = UUID.randomUUID();
    UUID orderB = UUID.randomUUID();
    UUID accountA = UUID.randomUUID();
    try (Connection admin = AuthTestSupport.admin()) {
      seedTenant(admin, tenantA, "shop-a");
      seedTenant(admin, tenantB, "shop-b");
      seedOrder(admin, tenantA, accountA, orderA);
      try (Statement statement = admin.createStatement();
          ResultSet rls =
              statement.executeQuery(
                  """
                  SELECT c.relrowsecurity, c.relforcerowsecurity
                  FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                  WHERE n.nspname = 'public' AND c.relname = 'order_hold_retry'
                  """)) {
        assertThat(rls.next()).isTrue();
        assertThat(rls.getBoolean("relrowsecurity")).isTrue();
        assertThat(rls.getBoolean("relforcerowsecurity")).isTrue();
      }
    }
    try (Connection app = AuthTestSupport.app()) {
      app.setAutoCommit(false);
      try (PreparedStatement tenant =
          app.prepareStatement("SELECT set_config('app.tenant_id', ?, true)")) {
        tenant.setString(1, tenantA.toString());
        tenant.execute();
      }
      try (PreparedStatement insert =
          app.prepareStatement(
              """
              INSERT INTO order_hold_retry (tenant_id, order_id, attempts, next_attempt_at, last_error)
              VALUES (?, ?, 1, now() + interval '1 minute', 'TEST')
              """)) {
        insert.setObject(1, tenantA);
        insert.setObject(2, orderA);
        insert.executeUpdate();
      }
      try (PreparedStatement cross =
          app.prepareStatement(
              """
              INSERT INTO order_hold_retry (tenant_id, order_id, attempts, next_attempt_at, last_error)
              VALUES (?, ?, 1, now() + interval '1 minute', 'TEST')
              """)) {
        cross.setObject(1, tenantB);
        cross.setObject(2, orderB);
        assertThatThrownBy(cross::executeUpdate).isInstanceOf(PSQLException.class);
      }
      app.rollback();
    }
  }

  private static void seedTenant(Connection admin, UUID tenantId, String shop) throws SQLException {
    try (PreparedStatement statement =
        admin.prepareStatement(
            "INSERT INTO tenant (id, name, tsf_shop_id, membership_tier, entitlement_status, ent_ver) "
                + "VALUES (?, ?, ?, 'PRO', 'ACTIVE', 1)")) {
      statement.setObject(1, tenantId);
      statement.setString(2, "T");
      statement.setString(3, shop);
      statement.executeUpdate();
    }
  }

  private static void seedOrder(Connection admin, UUID tenantId, UUID accountId, UUID orderId)
      throws SQLException {
    try (PreparedStatement account =
        admin.prepareStatement(
            """
            INSERT INTO channel_account (id, tenant_id, channel, external_shop_id, mode, status)
            VALUES (?, ?, 'TSF', 'ext', 'ACTIVE', 'CONNECTED')
            """)) {
      account.setObject(1, accountId);
      account.setObject(2, tenantId);
      account.executeUpdate();
    }
    try (PreparedStatement order =
        admin.prepareStatement(
            """
            INSERT INTO sales_order (
              id, tenant_id, channel_account_id, external_order_id, payment_status,
              payment_method, ordered_at
            ) VALUES (?, ?, ?, 'ORD-1', 'PAID', 'PREPAID', now())
            """)) {
      order.setObject(1, orderId);
      order.setObject(2, tenantId);
      order.setObject(3, accountId);
      order.executeUpdate();
    }
  }
}
