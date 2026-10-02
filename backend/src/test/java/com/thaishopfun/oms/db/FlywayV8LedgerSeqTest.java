package com.thaishopfun.oms.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * V8 ledger_seq backfill on databases that already hold {@code inventory_ledger} rows. Fresh
 * Testcontainers runs never hit the backfill; this class migrates to V7, seeds ledger history, then
 * applies V8.
 */
@ActiveProfiles("test")
@SpringBootTest
@Testcontainers
class FlywayV8LedgerSeqTest {

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
  void migrationBackfillsLedgerSeqOnTopOfV7WithExistingLedger() throws SQLException {
    String database = "oms_v8_upgrade_" + UUID.randomUUID().toString().replace("-", "");
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
    UUID product = UUID.randomUUID();
    UUID sku = UUID.randomUUID();
    UUID warehouseA = UUID.randomUUID();
    UUID warehouseB = UUID.randomUUID();
    UUID inventoryA = UUID.randomUUID();
    UUID inventoryB = UUID.randomUUID();
    UUID ledgerEarly = UUID.randomUUID();
    UUID ledgerMid = UUID.randomUUID();
    UUID ledgerLate = UUID.randomUUID();
    UUID ledgerB1 = UUID.randomUUID();
    UUID ledgerB2 = UUID.randomUUID();

    try {
      // Step 1: Stop at V7 and seed two inventory rows with ledger rows out of insert order.
      Flyway.configure()
          .dataSource(url, postgres.getUsername(), postgres.getPassword())
          .target("7")
          .load()
          .migrate();
      try (Connection conn =
          DriverManager.getConnection(url, postgres.getUsername(), postgres.getPassword())) {
        assertThat(versions(conn)).containsExactly("1", "2", "3", "4", "6", "7");
        insertTenant(conn, tenant);
        insertProduct(conn, product, tenant);
        insertSku(conn, sku, tenant, product);
        insertWarehouse(conn, warehouseA, tenant, "WH-A");
        insertWarehouse(conn, warehouseB, tenant, "WH-B");
        exec(
            conn,
            "INSERT INTO inventory (id, tenant_id, sku_id, warehouse_id, on_hand, reserved) "
                + "VALUES (?, ?, ?, ?, 5, 0)",
            inventoryA,
            tenant,
            sku,
            warehouseA);
        exec(
            conn,
            "INSERT INTO inventory (id, tenant_id, sku_id, warehouse_id, on_hand, reserved) "
                + "VALUES (?, ?, ?, ?, 2, 0)",
            inventoryB,
            tenant,
            sku,
            warehouseB);
        insertLedgerRow(
            conn, ledgerLate, tenant, sku, warehouseA, Instant.parse("2024-06-03T12:00:00Z"));
        insertLedgerRow(
            conn, ledgerEarly, tenant, sku, warehouseA, Instant.parse("2024-06-01T12:00:00Z"));
        insertLedgerRow(
            conn, ledgerMid, tenant, sku, warehouseA, Instant.parse("2024-06-02T12:00:00Z"));
        insertLedgerRow(
            conn, ledgerB2, tenant, sku, warehouseB, Instant.parse("2024-07-02T12:00:00Z"));
        insertLedgerRow(
            conn, ledgerB1, tenant, sku, warehouseB, Instant.parse("2024-07-01T12:00:00Z"));
      }

      // Step 2: V8 backfills ledger_seq under the append-only trigger disable.
      Flyway.configure()
          .dataSource(url, postgres.getUsername(), postgres.getPassword())
          .load()
          .migrate();
      try (Connection conn =
          DriverManager.getConnection(url, postgres.getUsername(), postgres.getPassword())) {
        assertThat(versions(conn)).containsExactly("1", "2", "3", "4", "6", "7", "8", "9", "10");
        assertThat(ledgerSeq(conn, ledgerEarly)).isEqualTo(1);
        assertThat(ledgerSeq(conn, ledgerMid)).isEqualTo(2);
        assertThat(ledgerSeq(conn, ledgerLate)).isEqualTo(3);
        assertThat(ledgerSeq(conn, ledgerB1)).isEqualTo(1);
        assertThat(ledgerSeq(conn, ledgerB2)).isEqualTo(2);
        assertThat(inventoryLedgerSeq(conn, tenant, sku, warehouseA)).isEqualTo(3);
        assertThat(inventoryLedgerSeq(conn, tenant, sku, warehouseB)).isEqualTo(2);
      }

      // Step 3: The next engine-style insert takes max + 1; append-only still blocks UPDATE.
      try (Connection conn =
          DriverManager.getConnection(url, postgres.getUsername(), postgres.getPassword())) {
        long next = nextLedgerSeq(conn, tenant, sku, warehouseA);
        assertThat(next).isEqualTo(4);
        insertLedgerWithSeq(
            conn,
            UUID.randomUUID(),
            tenant,
            sku,
            warehouseA,
            next,
            Instant.parse("2024-08-01T00:00:00Z"));
        assertThat(inventoryLedgerSeq(conn, tenant, sku, warehouseA)).isEqualTo(4);
        conn.setAutoCommit(false);
        assertSqlState(
            conn,
            "P0001",
            "append-only",
            () ->
                exec(conn, "UPDATE inventory_ledger SET actor = 'x' WHERE tenant_id = ?", tenant));
        conn.rollback();
      }
    } finally {
      try (Connection admin = openAdmin();
          Statement statement = admin.createStatement()) {
        statement.execute("DROP DATABASE IF EXISTS " + database + " WITH (FORCE)");
      }
    }
  }

  private Connection openAdmin() throws SQLException {
    return DriverManager.getConnection(
        postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
  }

  private static List<String> versions(Connection connection) throws SQLException {
    List<String> versions = new ArrayList<>();
    try (Statement statement = connection.createStatement();
        ResultSet rows =
            statement.executeQuery(
                "SELECT version, success FROM flyway_schema_history ORDER BY installed_rank")) {
      while (rows.next()) {
        assertThat(rows.getBoolean("success")).isTrue();
        versions.add(rows.getString("version"));
      }
    }
    return versions;
  }

  private static long ledgerSeq(Connection connection, UUID ledgerId) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement("SELECT ledger_seq FROM inventory_ledger WHERE id = ?")) {
      statement.setObject(1, ledgerId);
      try (ResultSet rows = statement.executeQuery()) {
        assertThat(rows.next()).isTrue();
        return rows.getLong(1);
      }
    }
  }

  private static long inventoryLedgerSeq(
      Connection connection, UUID tenantId, UUID skuId, UUID warehouseId) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT ledger_seq FROM inventory WHERE tenant_id = ? AND sku_id = ? AND warehouse_id = ?")) {
      statement.setObject(1, tenantId);
      statement.setObject(2, skuId);
      statement.setObject(3, warehouseId);
      try (ResultSet rows = statement.executeQuery()) {
        assertThat(rows.next()).isTrue();
        return rows.getLong(1);
      }
    }
  }

  private static long nextLedgerSeq(
      Connection connection, UUID tenantId, UUID skuId, UUID warehouseId) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            """
            UPDATE inventory SET ledger_seq = ledger_seq + 1
            WHERE tenant_id = ? AND sku_id = ? AND warehouse_id = ?
            RETURNING ledger_seq
            """)) {
      statement.setObject(1, tenantId);
      statement.setObject(2, skuId);
      statement.setObject(3, warehouseId);
      try (ResultSet rows = statement.executeQuery()) {
        assertThat(rows.next()).isTrue();
        return rows.getLong(1);
      }
    }
  }

  private static void insertTenant(Connection connection, UUID id) throws SQLException {
    exec(
        connection,
        "INSERT INTO tenant (id, name, tsf_shop_id, membership_tier, entitlement_status, ent_ver) "
            + "VALUES (?, 'Shop', ?, 'PRO', 'ACTIVE', 1)",
        id,
        "shop-" + id);
  }

  private static void insertProduct(Connection connection, UUID id, UUID tenantId)
      throws SQLException {
    exec(
        connection,
        "INSERT INTO product (id, tenant_id, name, status) VALUES (?, ?, 'Product', 'ACTIVE')",
        id,
        tenantId);
  }

  private static void insertSku(Connection connection, UUID id, UUID tenantId, UUID productId)
      throws SQLException {
    exec(
        connection,
        "INSERT INTO sku (id, tenant_id, product_id, sku_code, name, is_bundle) "
            + "VALUES (?, ?, ?, ?, 'SKU', false)",
        id,
        tenantId,
        productId,
        "SKU-" + id);
  }

  private static void insertWarehouse(Connection connection, UUID id, UUID tenantId, String code)
      throws SQLException {
    exec(
        connection,
        "INSERT INTO warehouse (id, tenant_id, code, name, is_default) "
            + "VALUES (?, ?, ?, ?, false)",
        id,
        tenantId,
        code,
        code);
  }

  private static void insertLedgerRow(
      Connection connection,
      UUID id,
      UUID tenantId,
      UUID skuId,
      UUID warehouseId,
      Instant createdAt)
      throws SQLException {
    exec(
        connection,
        "INSERT INTO inventory_ledger (id, tenant_id, sku_id, warehouse_id, delta_on_hand, "
            + "delta_reserved, reason, actor, created_at) VALUES (?, ?, ?, ?, 1, 0, "
            + "'RECEIVE', 'seed', ?)",
        id,
        tenantId,
        skuId,
        warehouseId,
        Timestamp.from(createdAt));
  }

  private static void insertLedgerWithSeq(
      Connection connection,
      UUID id,
      UUID tenantId,
      UUID skuId,
      UUID warehouseId,
      long ledgerSeq,
      Instant createdAt)
      throws SQLException {
    exec(
        connection,
        "INSERT INTO inventory_ledger (id, tenant_id, sku_id, warehouse_id, delta_on_hand, "
            + "delta_reserved, reason, actor, created_at, ledger_seq) VALUES (?, ?, ?, ?, 1, 0, "
            + "'RECEIVE', 'seed', ?, ?)",
        id,
        tenantId,
        skuId,
        warehouseId,
        Timestamp.from(createdAt),
        ledgerSeq);
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

  @FunctionalInterface
  private interface SqlAction {
    void run() throws SQLException;
  }

  private static void assertSqlState(
      Connection connection, String sqlState, String messagePart, SqlAction action)
      throws SQLException {
    Savepoint savepoint = connection.setSavepoint();
    try {
      action.run();
      fail("expected SQLSTATE " + sqlState);
    } catch (PSQLException exception) {
      assertThat(exception.getSQLState()).isEqualTo(sqlState);
      assertThat(exception.getMessage()).containsIgnoringCase(messagePart);
    } finally {
      connection.rollback(savepoint);
    }
  }
}
