package com.thaishopfun.oms.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class FlywayV18LabelCacheTest {
  @Container
  static PostgreSQLContainer postgres =
      new PostgreSQLContainer("postgres:16-alpine").withInitScript("db/test-oms-app-login.sql");

  @Test
  void labelCacheTableHasForcedRls() throws Exception {
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
      try (var rows =
          statement.executeQuery(
              """
              SELECT relrowsecurity, relforcerowsecurity, pg_get_userbyid(relowner)
              FROM pg_class WHERE oid = 'shipment_label_cache'::regclass
              """)) {
        assertThat(rows.next()).isTrue();
        assertThat(rows.getBoolean(1)).isTrue();
        assertThat(rows.getBoolean(2)).isTrue();
        assertThat(rows.getString(3)).isEqualTo("oms_migrator");
      }
    }
  }

  @Test
  void purgeDiscoveryFunctionExists() throws Exception {
    String url = postgres.getJdbcUrl();
    Flyway.configure()
        .dataSource(url, postgres.getUsername(), postgres.getPassword())
        .load()
        .migrate();
    try (Connection admin =
            DriverManager.getConnection(url, postgres.getUsername(), postgres.getPassword());
        var statement = admin.createStatement();
        var rows =
            statement.executeQuery(
                """
                SELECT pg_get_function_identity_arguments(p.oid)
                FROM pg_proc p
                JOIN pg_namespace n ON n.oid = p.pronamespace
                WHERE n.nspname = 'public' AND p.proname = 'list_tenants_with_label_cache_due'
                """)) {
      assertThat(rows.next()).isTrue();
      assertThat(rows.getString(1)).isEqualTo("p_now timestamp with time zone");
    }
  }
}
