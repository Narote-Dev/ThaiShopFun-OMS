package com.thaishopfun.oms.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.thaishopfun.oms.auth.AuthTestSupport;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** V6 {@code list_tenants_with_expired_reservations}: definer, ids only, bounded, oms_app only. */
@ActiveProfiles("test")
@SpringBootTest(properties = "spring.datasource.hikari.maximum-pool-size=2")
class FlywayV6StockExpiryClaimTest {

  private static final String FUNCTION =
      "public.list_tenants_with_expired_reservations(timestamptz, integer)";

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    AuthTestSupport.register(registry);
  }

  /** The runtime pool logs in as oms_app. */
  @Autowired DataSource appDataSource;

  @Test
  void functionIsAnIdOnlyDefinerOwnedByMaint() throws SQLException {
    try (Connection admin = AuthTestSupport.admin();
        Statement statement = admin.createStatement();
        ResultSet rows =
            statement.executeQuery(
                "SELECT pg_get_function_result(p.oid) AS result, "
                    + "pg_get_function_identity_arguments(p.oid) AS args, p.prosecdef, "
                    + "p.provolatile, p.proconfig::text AS config, l.lanname, "
                    + "pg_get_userbyid(p.proowner) AS owner, "
                    + "has_function_privilege('oms_app', p.oid, 'EXECUTE') AS app_exec, "
                    + "has_function_privilege('oms_migrator', p.oid, 'EXECUTE') AS migrator_exec, "
                    + "EXISTS (SELECT 1 FROM aclexplode(p.proacl) AS a WHERE a.grantee = 0) "
                    + "  OR p.proacl IS NULL AS public_exec "
                    + "FROM pg_proc p JOIN pg_language l ON l.oid = p.prolang "
                    + "WHERE p.oid = '"
                    + FUNCTION
                    + "'::regprocedure")) {
      assertThat(rows.next()).isTrue();
      // Step 1: Returns tenant_id only. SECURITY DEFINER, STABLE, sql, pinned search_path.
      assertThat(rows.getString("result")).isEqualTo("TABLE(tenant_id uuid)");
      assertThat(rows.getString("args"))
          .isEqualTo("p_now timestamp with time zone, p_limit integer");
      assertThat(rows.getBoolean("prosecdef")).isTrue();
      assertThat(rows.getString("provolatile")).isEqualTo("s");
      assertThat(rows.getString("lanname")).isEqualTo("sql");
      assertThat(rows.getString("config")).contains("search_path=pg_catalog, pg_temp");
      // Step 2: Owner oms_maint. oms_app can execute. PUBLIC (and so oms_migrator) cannot.
      assertThat(rows.getString("owner")).isEqualTo("oms_maint");
      assertThat(rows.getBoolean("app_exec")).isTrue();
      assertThat(rows.getBoolean("public_exec")).isFalse();
      assertThat(rows.getBoolean("migrator_exec")).isFalse();
    }
  }

  @Test
  void returnsExpiredTenantsAcrossEntitlementStatesWithoutContext() throws SQLException {
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC).plusYears(10);
    UUID active = UUID.randomUUID();
    UUID suspended = UUID.randomUUID();
    UUID future = UUID.randomUUID();
    UUID released = UUID.randomUUID();
    try (Connection admin = AuthTestSupport.admin()) {
      seed(admin, active, "ACTIVE", "ACTIVE", now.minusMinutes(1));
      seed(admin, suspended, "SUSPENDED", "ACTIVE", now.minusMinutes(1));
      seed(admin, future, "ACTIVE", "ACTIVE", now.plusMinutes(1));
      seed(admin, released, "GRACE", "RELEASED", now.minusMinutes(1));
    }

    try (Connection app = appDataSource.getConnection()) {
      // Step 1: oms_app with no tenant context sees no reservation rows directly.
      try (Statement statement = app.createStatement();
          ResultSet rows = statement.executeQuery("SELECT count(*) FROM stock_reservation")) {
        rows.next();
        assertThat(rows.getLong(1)).isZero();
      }
      // Step 2: The function returns one tenant_id column, for ACTIVE rows past p_now only.
      List<UUID> ids = new ArrayList<>();
      try (PreparedStatement statement =
          app.prepareStatement("SELECT * FROM list_tenants_with_expired_reservations(?, 1000)")) {
        statement.setObject(1, now);
        try (ResultSet rows = statement.executeQuery()) {
          ResultSetMetaData meta = rows.getMetaData();
          assertThat(meta.getColumnCount()).isEqualTo(1);
          assertThat(meta.getColumnName(1)).isEqualTo("tenant_id");
          while (rows.next()) {
            ids.add(rows.getObject(1, UUID.class));
          }
        }
      }
      assertThat(ids).contains(active, suspended).doesNotContain(future, released);
      assertThat(ids).doesNotHaveDuplicates();

      // Step 3: p_limit caps the result.
      try (PreparedStatement statement =
          app.prepareStatement(
              "SELECT count(*) FROM list_tenants_with_expired_reservations(?, 1)")) {
        statement.setObject(1, now);
        try (ResultSet rows = statement.executeQuery()) {
          rows.next();
          assertThat(rows.getLong(1)).isEqualTo(1);
        }
      }
    }
  }

  @Test
  void limitOutsideOneToThousandIsRejected() throws SQLException {
    try (Connection app = appDataSource.getConnection()) {
      for (String limit : List.of("0", "1001", "-5", "NULL")) {
        try (PreparedStatement statement =
            app.prepareStatement(
                "SELECT * FROM list_tenants_with_expired_reservations(now(), " + limit + ")")) {
          assertThatThrownBy(statement::executeQuery)
              .as("limit %s", limit)
              .isInstanceOf(SQLException.class)
              .hasMessageContaining("must be between 1 and 1000");
        }
      }
      for (String limit : List.of("1", "1000")) {
        try (PreparedStatement statement =
            app.prepareStatement(
                "SELECT * FROM list_tenants_with_expired_reservations(now(), " + limit + ")")) {
          statement.executeQuery().close();
        }
      }
    }
  }

  private static void seed(
      Connection admin, UUID tenant, String entitlement, String status, OffsetDateTime expiresAt)
      throws SQLException {
    UUID product = UUID.randomUUID();
    UUID sku = UUID.randomUUID();
    UUID warehouse = UUID.randomUUID();
    exec(
        admin,
        "INSERT INTO tenant (id, name, tsf_shop_id, membership_tier, entitlement_status, ent_ver) "
            + "VALUES (?, 'Shop', ?, 'PRO', ?, 1)",
        tenant,
        "shop-" + tenant,
        entitlement);
    exec(
        admin,
        "INSERT INTO product (id, tenant_id, name, status) VALUES (?, ?, 'P', 'ACTIVE')",
        product,
        tenant);
    exec(
        admin,
        "INSERT INTO sku (id, tenant_id, product_id, sku_code, name) VALUES (?, ?, ?, ?, 'S')",
        sku,
        tenant,
        product,
        "SKU-" + sku);
    exec(
        admin,
        "INSERT INTO warehouse (id, tenant_id, code, name) VALUES (?, ?, ?, 'W')",
        warehouse,
        tenant,
        "WH-" + warehouse);
    exec(
        admin,
        "INSERT INTO inventory (id, tenant_id, sku_id, warehouse_id, on_hand, reserved) "
            + "VALUES (?, ?, ?, ?, 10, ?)",
        UUID.randomUUID(),
        tenant,
        sku,
        warehouse,
        "ACTIVE".equals(status) ? 1 : 0);
    exec(
        admin,
        "INSERT INTO stock_reservation (id, tenant_id, owner_type, owner_ref, sku_id, "
            + "warehouse_id, qty, status, expires_at, reservation_group_id) "
            + "VALUES (?, ?, 'CHECKOUT', ?, ?, ?, 1, ?, ?, ?)",
        UUID.randomUUID(),
        tenant,
        "chk-" + tenant,
        sku,
        warehouse,
        status,
        expiresAt,
        UUID.randomUUID());
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
}
