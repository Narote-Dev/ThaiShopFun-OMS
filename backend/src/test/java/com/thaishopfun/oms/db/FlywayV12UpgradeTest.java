package com.thaishopfun.oms.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.thaishopfun.oms.auth.UuidV7;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** V11 database with a legacy mapped listing upgrades to V12 without constraint failure. */
@Testcontainers
class FlywayV12UpgradeTest {

  private static final String APP_PASSWORD = "oms-app-test-only";
  private static final String MIGRATOR_PASSWORD = "oms-migrator-test-only";

  @Container
  static PostgreSQLContainer postgres =
      new PostgreSQLContainer("postgres:16-alpine").withInitScript("db/test-oms-app-login.sql");

  @BeforeEach
  void enableTestLogins() throws SQLException {
    try (Connection admin = openAdmin();
        Statement statement = admin.createStatement()) {
      statement.execute("ALTER ROLE oms_app LOGIN PASSWORD '" + APP_PASSWORD + "'");
      statement.execute("ALTER ROLE oms_migrator LOGIN PASSWORD '" + MIGRATOR_PASSWORD + "'");
    }
  }

  @Test
  void v12BackfillsLegacyMappedListing() throws SQLException {
    String database = "oms_v12_upgrade_" + UUID.randomUUID().toString().replace("-", "");
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
    UUID tenant = UuidV7.generate();
    UUID account = UuidV7.generate();
    UUID product = UuidV7.generate();
    UUID sku = UuidV7.generate();
    UUID listing = UuidV7.generate();
    try {
      Flyway.configure()
          .dataSource(url, "oms_migrator", MIGRATOR_PASSWORD)
          .locations("classpath:db/migration")
          .target("11")
          .load()
          .migrate();
      try (Connection admin = openAdmin(database);
          PreparedStatement tenantStmt =
              admin.prepareStatement(
                  "INSERT INTO tenant (id, name, tsf_shop_id, entitlement_status) VALUES (?, 't', 'shop', 'ACTIVE')");
          PreparedStatement accountStmt =
              admin.prepareStatement(
                  "INSERT INTO channel_account (id, tenant_id, channel, external_shop_id, mode, status) VALUES (?, ?, 'TSF', 'shop', 'ACTIVE', 'CONNECTED')");
          PreparedStatement productStmt =
              admin.prepareStatement(
                  "INSERT INTO product (id, tenant_id, name, status) VALUES (?, ?, 'p', 'ACTIVE')");
          PreparedStatement skuStmt =
              admin.prepareStatement(
                  "INSERT INTO sku (id, tenant_id, product_id, sku_code, name, is_bundle) VALUES (?, ?, ?, 'LEGACY', 'Legacy', false)");
          PreparedStatement listingStmt =
              admin.prepareStatement(
                  """
                  INSERT INTO channel_listing (
                    id, tenant_id, channel_account_id, sku_id, external_sku_id, stock_control
                  ) VALUES (?, ?, ?, ?, 'L-legacy', true)
                  """)) {
        tenantStmt.setObject(1, tenant);
        tenantStmt.executeUpdate();
        accountStmt.setObject(1, account);
        accountStmt.setObject(2, tenant);
        accountStmt.executeUpdate();
        productStmt.setObject(1, product);
        productStmt.setObject(2, tenant);
        productStmt.executeUpdate();
        skuStmt.setObject(1, sku);
        skuStmt.setObject(2, tenant);
        skuStmt.setObject(3, product);
        skuStmt.executeUpdate();
        listingStmt.setObject(1, listing);
        listingStmt.setObject(2, tenant);
        listingStmt.setObject(3, account);
        listingStmt.setObject(4, sku);
        listingStmt.executeUpdate();
      }
      Flyway.configure()
          .dataSource(url, "oms_migrator", MIGRATOR_PASSWORD)
          .locations("classpath:db/migration")
          .load()
          .migrate();
      try (Connection app = openApp(database);
          PreparedStatement statement =
              app.prepareStatement(
                  "SELECT mapping_source, mapped_at IS NOT NULL AS mapped FROM channel_listing WHERE id = ?")) {
        statement.setObject(1, listing);
        try (ResultSet rows = statement.executeQuery()) {
          assertThat(rows.next()).isTrue();
          assertThat(rows.getString("mapping_source")).isEqualTo("MANUAL");
          assertThat(rows.getBoolean("mapped")).isTrue();
        }
      }
    } finally {
      try (Connection admin = openAdmin();
          Statement statement = admin.createStatement()) {
        statement.execute("DROP DATABASE IF EXISTS " + database);
      }
    }
  }

  private static Connection openAdmin() throws SQLException {
    return openAdmin(postgres.getDatabaseName());
  }

  private static Connection openAdmin(String database) throws SQLException {
    return DriverManager.getConnection(
        "jdbc:postgresql://"
            + postgres.getHost()
            + ":"
            + postgres.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT)
            + "/"
            + database,
        postgres.getUsername(),
        postgres.getPassword());
  }

  private static Connection openApp(String database) throws SQLException {
    return DriverManager.getConnection(
        "jdbc:postgresql://"
            + postgres.getHost()
            + ":"
            + postgres.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT)
            + "/"
            + database,
        "oms_app",
        APP_PASSWORD);
  }
}
