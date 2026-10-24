package com.thaishopfun.oms.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class FlywayV15StockPushTest {
  @Container
  static PostgreSQLContainer postgres =
      new PostgreSQLContainer("postgres:16-alpine").withInitScript("db/test-oms-app-login.sql");

  @Test
  void emptyDatabaseMigratesToLatestWithForcedRlsAndIdsOnlyDiscovery() throws Exception {
    String url = database();
    migrate(url, null);
    try (Connection admin = admin(url);
        var statement = admin.createStatement()) {
      try (var rows =
          statement.executeQuery(
              """
          SELECT relrowsecurity, relforcerowsecurity, pg_get_userbyid(relowner)
          FROM pg_class WHERE oid = 'stock_push_pending'::regclass
          """)) {
        assertThat(rows.next()).isTrue();
        assertThat(rows.getBoolean(1)).isTrue();
        assertThat(rows.getBoolean(2)).isTrue();
        assertThat(rows.getString(3)).isEqualTo("oms_migrator");
      }
      try (var rows =
          statement.executeQuery(
              "SELECT version FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 1")) {
        assertThat(rows.next()).isTrue();
        assertThat(rows.getString(1)).isEqualTo("16");
      }
      try (var rows = statement.executeQuery("SELECT * FROM list_stock_push_accounts(true)")) {
        assertThat(rows.getMetaData().getColumnCount()).isEqualTo(2);
        assertThat(rows.getMetaData().getColumnName(1)).isEqualTo("tenant_id");
        assertThat(rows.getMetaData().getColumnName(2)).isEqualTo("channel_account_id");
      }
      try (var rows =
          statement.executeQuery(
              """
          SELECT prosecdef, pg_get_userbyid(proowner), proconfig::text,
            has_function_privilege('oms_app', oid, 'EXECUTE')
          FROM pg_proc WHERE proname = 'list_stock_push_accounts'
          """)) {
        assertThat(rows.next()).isTrue();
        assertThat(rows.getBoolean(1)).isTrue();
        assertThat(rows.getString(2)).isEqualTo("oms_maint");
        assertThat(rows.getString(3)).contains("pg_catalog, pg_temp");
        assertThat(rows.getBoolean(4)).isTrue();
      }
    }
    try (Connection app = DriverManager.getConnection(url, "oms_app", "oms-app-test-only");
        var statement = app.createStatement()) {
      assertThatThrownBy(
              () ->
                  statement.executeUpdate(
                      """
          INSERT INTO stock_push_pending(tenant_id, channel_account_id, listing_id)
          VALUES (gen_random_uuid(), gen_random_uuid(), gen_random_uuid())
          """))
          .isInstanceOf(SQLException.class)
          .extracting(e -> ((SQLException) e).getSQLState())
          .isEqualTo("42501");
    }
  }

  @Test
  void upgradePreservesExistingPushMarkersAndMappingCaptureIsTenantIsolated() throws Exception {
    String url = database();
    migrate(url, "14");
    UUID tenant = UUID.randomUUID(), account = UUID.randomUUID(), listing = UUID.randomUUID();
    try (Connection admin = admin(url)) {
      try (var statement =
          admin.prepareStatement(
              """
          INSERT INTO tenant(id, name, tsf_shop_id, membership_tier, entitlement_status, ent_ver)
          VALUES (?, 'migration', ?, 'PRO', 'ACTIVE', 1)
          """)) {
        statement.setObject(1, tenant);
        statement.setString(2, tenant.toString());
        statement.executeUpdate();
      }
      try (var statement =
          admin.prepareStatement(
              """
          INSERT INTO channel_account(id, tenant_id, channel, external_shop_id, mode, status)
          VALUES (?, ?, 'TSF', ?, 'ACTIVE', 'CONNECTED')
          """)) {
        statement.setObject(1, account);
        statement.setObject(2, tenant);
        statement.setString(3, account.toString());
        statement.executeUpdate();
      }
      try (var statement =
          admin.prepareStatement(
              """
          INSERT INTO channel_listing(id, tenant_id, channel_account_id, external_sku_id, last_exposed_qty, last_pushed_version)
          VALUES (?, ?, ?, 'legacy', 12, 99)
          """)) {
        statement.setObject(1, listing);
        statement.setObject(2, tenant);
        statement.setObject(3, account);
        statement.executeUpdate();
      }
    }
    migrate(url, null);
    try (Connection app = DriverManager.getConnection(url, "oms_app", "oms-app-test-only")) {
      app.setAutoCommit(false);
      try (var statement = app.prepareStatement("SELECT set_config('app.tenant_id', ?, true)")) {
        statement.setString(1, tenant.toString());
        statement.execute();
      }
      try (var statement = app.createStatement();
          var rows =
              statement.executeQuery(
                  "SELECT last_exposed_qty, last_pushed_version FROM channel_listing")) {
        assertThat(rows.next()).isTrue();
        assertThat(rows.getInt(1)).isEqualTo(12);
        assertThat(rows.getLong(2)).isEqualTo(99);
      }
      try (var statement = app.createStatement()) {
        statement.executeUpdate("UPDATE channel_listing SET seller_sku = 'mapped-stub'");
        try (var rows =
            statement.executeQuery(
                "SELECT dirty_since, mapping_revision FROM stock_push_pending")) {
          assertThat(rows.next()).isTrue();
          assertThat(rows.getObject(1)).isNotNull();
          assertThat(rows.getLong(2)).isEqualTo(1);
        }
        // Another tenant cannot see or change the state, even with the same listing id.
        statement.execute("SELECT set_config('app.tenant_id', '" + UUID.randomUUID() + "', true)");
        try (var rows = statement.executeQuery("SELECT count(*) FROM stock_push_pending")) {
          rows.next();
          assertThat(rows.getInt(1)).isZero();
        }
        assertThat(statement.executeUpdate("UPDATE stock_push_pending SET dirty_since = NULL"))
            .isZero();
      }
      app.rollback();
    }
  }

  private String database() throws SQLException {
    String name = "t15_" + UUID.randomUUID().toString().replace("-", "");
    try (Connection admin = admin(postgres.getJdbcUrl());
        var statement = admin.createStatement()) {
      statement.execute("CREATE DATABASE " + name);
      statement.execute("ALTER ROLE oms_app LOGIN PASSWORD 'oms-app-test-only'");
    }
    return "jdbc:postgresql://"
        + postgres.getHost()
        + ":"
        + postgres.getMappedPort(5432)
        + "/"
        + name;
  }

  private Connection admin(String url) throws SQLException {
    return DriverManager.getConnection(url, postgres.getUsername(), postgres.getPassword());
  }

  private void migrate(String url, String target) {
    var config =
        Flyway.configure()
            .dataSource(url, postgres.getUsername(), postgres.getPassword())
            .locations("classpath:db/migration");
    if (target != null) config.target(target);
    config.load().migrate();
  }
}
