package com.thaishopfun.oms.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.thaishopfun.oms.auth.AuthTestSupport;
import com.thaishopfun.oms.auth.UuidV7;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
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
class FlywayV10ChannelAccountTest {

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    AuthTestSupport.register(registry);
  }

  @Container
  static PostgreSQLContainer upgradePostgres =
      new PostgreSQLContainer("postgres:16-alpine").withInitScript("db/test-oms-app-login.sql");

  @Test
  void flywayHistoryIncludesVersion10() throws SQLException {
    try (Connection admin = AuthTestSupport.admin();
        Statement statement = admin.createStatement();
        ResultSet history =
            statement.executeQuery(
                "SELECT version, success FROM flyway_schema_history ORDER BY installed_rank")) {
      // Step 1: Every applied migration succeeded and V10 is present after startup.
      List<String> versions = new ArrayList<>();
      while (history.next()) {
        assertThat(history.getBoolean("success")).isTrue();
        versions.add(history.getString("version"));
      }
      assertThat(versions).contains("10");
    }
  }

  @Test
  void provisionTenantCreatesTsfChannelAccount() throws SQLException {
    String shopId = "shop-v10-" + UuidV7.generate();
    UUID tenantId;
    try (Connection app = AuthTestSupport.app();
        PreparedStatement statement =
            app.prepareStatement(
                """
                SELECT provision_tenant(?, ?, ?, ?, ?, ?)
                """)) {
      // Step 1: JIT provision returns an id and inserts the default TSF channel_account row.
      statement.setString(1, shopId);
      statement.setString(2, "Shop V10");
      statement.setString(3, "PRO");
      statement.setString(4, "ACTIVE");
      statement.setObject(5, null);
      statement.setLong(6, 1L);
      try (ResultSet rs = statement.executeQuery()) {
        assertThat(rs.next()).isTrue();
        tenantId = rs.getObject(1, UUID.class);
      }
    }
    try (Connection admin = AuthTestSupport.admin();
        PreparedStatement statement =
            admin.prepareStatement(
                """
                SELECT mode, status, tenant_id
                FROM channel_account
                WHERE channel = 'TSF' AND external_shop_id = ?
                """)) {
      statement.setString(1, shopId);
      try (ResultSet rs = statement.executeQuery()) {
        assertThat(rs.next()).isTrue();
        assertThat(rs.getString("mode")).isEqualTo("OBSERVE");
        assertThat(rs.getString("status")).isEqualTo("CONNECTED");
        assertThat(rs.getObject("tenant_id", UUID.class)).isEqualTo(tenantId);
        assertThat(rs.next()).isFalse();
      }
    }
  }

  @Test
  void secondProvisionDoesNotDuplicateOrResetMode() throws SQLException {
    String shopId = "shop-v10-dup-" + UuidV7.generate();
    UUID tenantId;
    try (Connection app = AuthTestSupport.app();
        PreparedStatement statement =
            app.prepareStatement("SELECT provision_tenant(?, ?, ?, ?, ?, ?)")) {
      statement.setString(1, shopId);
      statement.setString(2, "First");
      statement.setString(3, "PRO");
      statement.setString(4, "ACTIVE");
      statement.setObject(5, null);
      statement.setLong(6, 1L);
      try (ResultSet rs = statement.executeQuery()) {
        assertThat(rs.next()).isTrue();
        tenantId = rs.getObject(1, UUID.class);
      }
    }
    try (Connection admin = AuthTestSupport.admin();
        PreparedStatement update =
            admin.prepareStatement(
                "UPDATE channel_account SET mode = 'ACTIVE' WHERE channel = 'TSF' AND"
                    + " external_shop_id = ?")) {
      // Step 1: Move the account out of OBSERVE; a repeat provision must not rewind it.
      update.setString(1, shopId);
      assertThat(update.executeUpdate()).isEqualTo(1);
    }
    try (Connection app = AuthTestSupport.app();
        PreparedStatement statement =
            app.prepareStatement("SELECT provision_tenant(?, ?, ?, ?, ?, ?)")) {
      statement.setString(1, shopId);
      statement.setString(2, "Second name");
      statement.setString(3, "PRO");
      statement.setString(4, "ACTIVE");
      statement.setObject(5, null);
      statement.setLong(6, 2L);
      try (ResultSet rs = statement.executeQuery()) {
        assertThat(rs.next()).isTrue();
        assertThat(rs.getObject(1, UUID.class)).isEqualTo(tenantId);
      }
    }
    try (Connection admin = AuthTestSupport.admin();
        PreparedStatement count =
            admin.prepareStatement(
                """
                SELECT count(*) AS c, max(mode) AS mode
                FROM channel_account
                WHERE channel = 'TSF' AND external_shop_id = ?
                """)) {
      count.setString(1, shopId);
      try (ResultSet rs = count.executeQuery()) {
        assertThat(rs.next()).isTrue();
        assertThat(rs.getLong("c")).isEqualTo(1);
        assertThat(rs.getString("mode")).isEqualTo("ACTIVE");
      }
    }
  }

  @Test
  void upgradeBackfillsMissingTsfChannelAccount() throws SQLException {
    String database = "oms_v10_upgrade_" + UUID.randomUUID().toString().replace("-", "");
    try (Connection admin = openUpgradeAdmin();
        Statement statement = admin.createStatement()) {
      statement.execute("CREATE DATABASE " + database);
    }
    String url = upgradeJdbcUrl(database);
    UUID tenant = UUID.randomUUID();
    String shopId = "shop-backfill-" + tenant;
    try {
      // Step 1: Stop at V9 with a tenant row but no channel_account.
      Flyway.configure()
          .dataSource(url, upgradePostgres.getUsername(), upgradePostgres.getPassword())
          .target("9")
          .load()
          .migrate();
      try (Connection conn =
          DriverManager.getConnection(
              url, upgradePostgres.getUsername(), upgradePostgres.getPassword())) {
        exec(
            conn,
            """
            INSERT INTO tenant (id, name, tsf_shop_id, membership_tier, entitlement_status, ent_ver)
            VALUES (?, 'Backfill', ?, 'PRO', 'ACTIVE', 1)
            """,
            tenant,
            shopId);
      }
      Flyway.configure()
          .dataSource(url, upgradePostgres.getUsername(), upgradePostgres.getPassword())
          .load()
          .migrate();
      try (Connection conn =
              DriverManager.getConnection(
                  url, upgradePostgres.getUsername(), upgradePostgres.getPassword());
          PreparedStatement statement =
              conn.prepareStatement(
                  """
                  SELECT mode, status, tenant_id
                  FROM channel_account
                  WHERE channel = 'TSF' AND external_shop_id = ?
                  """)) {
        // Step 2: V10 backfill creates OBSERVE + CONNECTED for legacy tenants.
        statement.setString(1, shopId);
        try (ResultSet rs = statement.executeQuery()) {
          assertThat(rs.next()).isTrue();
          assertThat(rs.getString("mode")).isEqualTo("OBSERVE");
          assertThat(rs.getString("status")).isEqualTo("CONNECTED");
          assertThat(rs.getObject("tenant_id", UUID.class)).isEqualTo(tenant);
        }
      }
    } finally {
      try (Connection admin = openUpgradeAdmin();
          Statement statement = admin.createStatement()) {
        statement.execute("DROP DATABASE IF EXISTS " + database + " WITH (FORCE)");
      }
    }
  }

  @Test
  void resolveTenantUsesChannelAccountAndTsfFallback() throws SQLException {
    UUID tenant = UUID.randomUUID();
    String legacyShop = "legacy-" + tenant;
    String mappedShop = "mapped-" + tenant;
    try (Connection admin = AuthTestSupport.admin()) {
      exec(
          admin,
          """
          INSERT INTO tenant (id, name, tsf_shop_id, membership_tier, entitlement_status, ent_ver)
          VALUES (?, 'Resolve', ?, 'PRO', 'ACTIVE', 1)
          """,
          tenant,
          legacyShop);
      UUID accountId = UUID.randomUUID();
      exec(
          admin,
          """
          INSERT INTO channel_account (id, tenant_id, channel, external_shop_id, mode, status)
          VALUES (?, ?, 'TSF', ?, 'OBSERVE', 'CONNECTED')
          """,
          accountId,
          tenant,
          mappedShop);
    }
    try (Connection app = AuthTestSupport.app();
        PreparedStatement statement = app.prepareStatement("SELECT resolve_tenant(?, ?)")) {
      // Step 1: Prefer channel_account.external_shop_id.
      statement.setString(1, "TSF");
      statement.setString(2, mappedShop);
      try (ResultSet rs = statement.executeQuery()) {
        assertThat(rs.next()).isTrue();
        assertThat(rs.getObject(1, UUID.class)).isEqualTo(tenant);
      }
      try (Connection admin = AuthTestSupport.admin();
          PreparedStatement delete =
              admin.prepareStatement("DELETE FROM channel_account WHERE external_shop_id = ?")) {
        delete.setString(1, mappedShop);
        delete.executeUpdate();
      }
      // Step 2: TSF falls back to tenant.tsf_shop_id when no account row exists.
      statement.setString(2, legacyShop);
      try (ResultSet rs = statement.executeQuery()) {
        assertThat(rs.next()).isTrue();
        assertThat(rs.getObject(1, UUID.class)).isEqualTo(tenant);
      }
      // Step 3: Unknown shops return null.
      statement.setString(2, "missing-" + tenant);
      try (ResultSet rs = statement.executeQuery()) {
        assertThat(rs.next()).isTrue();
        assertThat(rs.getObject(1, UUID.class)).isNull();
      }
    }
  }

  @Test
  void securityDefinerFunctionsOwnedByMaintWithAppExecuteOnly() throws SQLException {
    try (Connection admin = AuthTestSupport.admin();
        PreparedStatement statement =
            admin.prepareStatement(
                """
                SELECT p.proname,
                       pg_get_userbyid(p.proowner) AS owner,
                       has_function_privilege('oms_app', p.oid, 'EXECUTE') AS app_exec,
                       EXISTS (SELECT 1 FROM aclexplode(p.proacl) AS a WHERE a.grantee = 0)
                         OR p.proacl IS NULL AS public_exec
                FROM pg_proc AS p
                JOIN pg_namespace AS n ON n.oid = p.pronamespace
                WHERE n.nspname = 'public'
                  AND p.proname IN ('provision_tenant', 'resolve_tenant')
                ORDER BY p.proname
                """)) {
      // Step 1: Cross-tenant helpers stay on oms_maint with EXECUTE for oms_app only.
      try (ResultSet rs = statement.executeQuery()) {
        assertThat(rs.next()).isTrue();
        assertThat(rs.getString("proname")).isEqualTo("provision_tenant");
        assertThat(rs.getString("owner")).isEqualTo("oms_maint");
        assertThat(rs.getBoolean("app_exec")).isTrue();
        assertThat(rs.getBoolean("public_exec")).isFalse();
        assertThat(rs.next()).isTrue();
        assertThat(rs.getString("proname")).isEqualTo("resolve_tenant");
        assertThat(rs.getString("owner")).isEqualTo("oms_maint");
        assertThat(rs.getBoolean("app_exec")).isTrue();
        assertThat(rs.getBoolean("public_exec")).isFalse();
        assertThat(rs.next()).isFalse();
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

  private Connection openUpgradeAdmin() throws SQLException {
    return DriverManager.getConnection(
        upgradePostgres.getJdbcUrl(), upgradePostgres.getUsername(), upgradePostgres.getPassword());
  }

  private String upgradeJdbcUrl(String database) {
    return "jdbc:postgresql://"
        + upgradePostgres.getHost()
        + ":"
        + upgradePostgres.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT)
        + "/"
        + database;
  }
}
