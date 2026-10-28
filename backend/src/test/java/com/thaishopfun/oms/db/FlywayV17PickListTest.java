package com.thaishopfun.oms.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
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
    Flyway.configure().dataSource(url, postgres.getUsername(), postgres.getPassword()).load().migrate();
    try (Connection admin = DriverManager.getConnection(url, postgres.getUsername(), postgres.getPassword());
        var statement = admin.createStatement()) {
      try (var rows =
          statement.executeQuery(
              "SELECT version FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 1")) {
        assertThat(rows.next()).isTrue();
        assertThat(rows.getString(1)).isEqualTo("17");
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
      UUID tenantId = UUID.randomUUID();
      statement.execute(
          """
          INSERT INTO tenant (id, name, tsf_shop_id, membership_tier, entitlement_status, ent_ver)
          VALUES ('%s', 't', 'shop-%s', 'BASIC', 'ACTIVE', 1)
          """
              .formatted(tenantId, tenantId));
      try (var rows = statement.executeQuery("SELECT count(*) FROM pick_list")) {
        rows.next();
        assertThat(rows.getInt(1)).isZero();
      }
    }
  }
}
