package com.thaishopfun.oms.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** V8 database with seeded reservations upgrades to V9 and resolves tenant by group id. */
@ActiveProfiles("test")
@SpringBootTest
@Testcontainers
class FlywayV9UpgradeTest {

  private static final String APP_PASSWORD = "oms-app-test-only";
  private static final String MIGRATOR_PASSWORD = "oms-migrator-test-only";

  @Container
  static PostgreSQLContainer postgres =
      new PostgreSQLContainer("postgres:16-alpine").withInitScript("db/test-oms-app-login.sql");

  @DynamicPropertySource
  static void runtimeIsOmsApp(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", () -> "oms_app");
    registry.add("spring.datasource.password", () -> APP_PASSWORD);
    registry.add("spring.flyway.url", postgres::getJdbcUrl);
    registry.add("spring.flyway.user", postgres::getUsername);
    registry.add("spring.flyway.password", postgres::getPassword);
    registry.add("oms.inbox.worker-enabled", () -> "false");
  }

  @BeforeEach
  void enableTestLogins() throws SQLException {
    try (Connection admin = openAdmin();
        Statement statement = admin.createStatement()) {
      statement.execute("ALTER ROLE oms_app LOGIN PASSWORD '" + APP_PASSWORD + "'");
      statement.execute("ALTER ROLE oms_migrator LOGIN PASSWORD '" + MIGRATOR_PASSWORD + "'");
    }
  }

  @Test
  void v9ResolveTenantWorksAfterV8ReservationsSeeded() throws SQLException {
    String database = "oms_v9_upgrade_" + UUID.randomUUID().toString().replace("-", "");
    try (Connection admin = openAdmin();
        Statement statement = admin.createStatement()) {
      statement.execute("CREATE DATABASE " + database);
    }
    String url =
        "jdbc:postgresql://"
            + postgres.getHost()
            + ":"
            + postgres.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT)
            + "/"
            + database;
    UUID tenant = UUID.randomUUID();
    UUID group = UUID.randomUUID();
    UUID sku = UUID.randomUUID();
    UUID warehouse = UUID.randomUUID();
    UUID inventory = UUID.randomUUID();
    UUID reservation = UUID.randomUUID();
    try {
      Flyway.configure()
          .dataSource(url, postgres.getUsername(), postgres.getPassword())
          .target("8")
          .load()
          .migrate();
      try (Connection conn =
          DriverManager.getConnection(url, postgres.getUsername(), postgres.getPassword())) {
        exec(
            conn,
            "INSERT INTO tenant (id, name, tsf_shop_id, membership_tier, entitlement_status, ent_ver) "
                + "VALUES (?, 'T', ?, 'PRO', 'ACTIVE', 1)",
            tenant,
            "shop-" + tenant);
        UUID product = UUID.randomUUID();
        exec(
            conn,
            "INSERT INTO product (id, tenant_id, name, status) VALUES (?, ?, 'P', 'ACTIVE')",
            product,
            tenant);
        exec(
            conn,
            "INSERT INTO sku (id, tenant_id, product_id, sku_code, name, is_bundle) "
                + "VALUES (?, ?, ?, 'SKU', 'S', false)",
            sku,
            tenant,
            product);
        exec(
            conn,
            "INSERT INTO warehouse (id, tenant_id, code, name, is_default) VALUES (?, ?, 'W', 'W', true)",
            warehouse,
            tenant);
        exec(
            conn,
            "INSERT INTO inventory (id, tenant_id, sku_id, warehouse_id, on_hand, reserved) "
                + "VALUES (?, ?, ?, ?, 5, 1)",
            inventory,
            tenant,
            sku,
            warehouse);
        exec(
            conn,
            """
            INSERT INTO stock_reservation
              (id, tenant_id, owner_type, owner_ref, sku_id, warehouse_id, qty, status,
               expires_at, reservation_group_id)
            VALUES (?, ?, 'CHECKOUT', 'chk-seed', ?, ?, 1, 'ACTIVE', ?, ?)
            """,
            reservation,
            tenant,
            sku,
            warehouse,
            OffsetDateTime.ofInstant(
                java.time.Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC),
            group);
      }
      Flyway.configure()
          .dataSource(url, postgres.getUsername(), postgres.getPassword())
          .load()
          .migrate();
      try (Connection app = DriverManager.getConnection(url, "oms_app", APP_PASSWORD);
          PreparedStatement statement =
              app.prepareStatement("SELECT resolve_reservation_tenant(?)")) {
        statement.setObject(1, group);
        try (ResultSet rs = statement.executeQuery()) {
          assertThat(rs.next()).isTrue();
          assertThat(rs.getObject(1, UUID.class)).isEqualTo(tenant);
        }
      }
      try (Connection conn =
              DriverManager.getConnection(url, postgres.getUsername(), postgres.getPassword());
          PreparedStatement statement =
              conn.prepareStatement(
                  """
                  SELECT indexname FROM pg_indexes
                  WHERE tablename = 'stock_reservation'
                    AND indexname LIKE '%reservation_group%'
                  """)) {
        try (ResultSet rs = statement.executeQuery()) {
          List<String> names = new java.util.ArrayList<>();
          while (rs.next()) {
            names.add(rs.getString(1));
          }
          assertThat(names).isNotEmpty();
        }
      }
    } finally {
      try (Connection admin = openAdmin();
          Statement statement = admin.createStatement()) {
        statement.execute("DROP DATABASE IF EXISTS " + database + " WITH (FORCE)");
      }
    }
  }

  private static void exec(Connection conn, String sql, Object... params) throws SQLException {
    try (PreparedStatement statement = conn.prepareStatement(sql)) {
      for (int i = 0; i < params.length; i++) {
        statement.setObject(i + 1, params[i]);
      }
      statement.executeUpdate();
    }
  }

  private Connection openAdmin() throws SQLException {
    return DriverManager.getConnection(
        postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
  }
}
