package com.thaishopfun.oms.db;

import static org.assertj.core.api.Assertions.assertThat;

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
class FlywayV17PickListTest {
  @Container
  static PostgreSQLContainer postgres =
      new PostgreSQLContainer("postgres:16-alpine").withInitScript("db/test-oms-app-login.sql");

  @Test
  void pickListTablesHaveForcedRlsAndGrants() throws Exception {
    String url = postgres.getJdbcUrl();
    Flyway.configure()
        .dataSource(url, postgres.getUsername(), postgres.getPassword())
        .load()
        .migrate();
    try (Connection admin =
            DriverManager.getConnection(url, postgres.getUsername(), postgres.getPassword());
        var statement = admin.createStatement()) {
      try (var rows =
          statement.executeQuery(
              "SELECT version FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 1")) {
        assertThat(rows.next()).isTrue();
        assertThat(rows.getString(1)).isEqualTo("18");
      }
      for (String table : new String[] {"pick_list", "pick_list_order", "order_pack_progress"}) {
        try (var rows =
            statement.executeQuery(
                """
                SELECT relrowsecurity, relforcerowsecurity, pg_get_userbyid(relowner)
                FROM pg_class WHERE oid = '%s'::regclass
                """
                    .formatted(table))) {
          assertThat(rows.next()).isTrue();
          assertThat(rows.getBoolean(1)).isTrue();
          assertThat(rows.getBoolean(2)).isTrue();
          assertThat(rows.getString(3)).isEqualTo("oms_migrator");
        }
      }
    }
  }

  @Test
  void pickListRowsAreTenantIsolated() throws Exception {
    String url = postgres.getJdbcUrl();
    Flyway.configure()
        .dataSource(url, postgres.getUsername(), postgres.getPassword())
        .load()
        .migrate();
    UUID tenantA = UUID.randomUUID();
    UUID tenantB = UUID.randomUUID();
    UUID warehouseA = UUID.randomUUID();
    UUID pickListA = UUID.randomUUID();
    try (Connection admin =
        DriverManager.getConnection(url, postgres.getUsername(), postgres.getPassword())) {
      insertTenant(admin, tenantA);
      insertTenant(admin, tenantB);
      try (var statement =
          admin.prepareStatement(
              """
          INSERT INTO warehouse (id, tenant_id, code, name, is_default)
          VALUES (?, ?, 'WH-A', 'A', true)
          """)) {
        statement.setObject(1, warehouseA);
        statement.setObject(2, tenantA);
        statement.executeUpdate();
      }
      try (var statement =
          admin.prepareStatement(
              """
          INSERT INTO pick_list (id, tenant_id, warehouse_id) VALUES (?, ?, ?)
          """)) {
        statement.setObject(1, pickListA);
        statement.setObject(2, tenantA);
        statement.setObject(3, warehouseA);
        statement.executeUpdate();
      }
    }
    try (Connection app = DriverManager.getConnection(url, "oms_app", "oms-app-test-only")) {
      app.setAutoCommit(false);
      setTenant(app, tenantA);
      try (var rows = app.createStatement().executeQuery("SELECT count(*) FROM pick_list")) {
        rows.next();
        assertThat(rows.getInt(1)).isEqualTo(1);
      }
      setTenant(app, tenantB);
      try (var rows = app.createStatement().executeQuery("SELECT count(*) FROM pick_list")) {
        rows.next();
        assertThat(rows.getInt(1)).isZero();
      }
      app.rollback();
    }
  }

  private static void insertTenant(Connection admin, UUID tenantId) throws SQLException {
    try (var statement =
        admin.prepareStatement(
            """
            INSERT INTO tenant (id, name, tsf_shop_id, membership_tier, entitlement_status, ent_ver)
            VALUES (?, 't', ?, 'BASIC', 'ACTIVE', 1)
            """)) {
      statement.setObject(1, tenantId);
      statement.setString(2, tenantId.toString());
      statement.executeUpdate();
    }
  }

  private static void setTenant(Connection app, UUID tenantId) throws SQLException {
    try (var statement = app.prepareStatement("SELECT set_config('app.tenant_id', ?, true)")) {
      statement.setString(1, tenantId.toString());
      statement.execute();
    }
  }
}
