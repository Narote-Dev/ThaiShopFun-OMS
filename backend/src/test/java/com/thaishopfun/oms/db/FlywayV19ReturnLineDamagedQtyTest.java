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
class FlywayV19ReturnLineDamagedQtyTest {
  @Container
  static PostgreSQLContainer postgres =
      new PostgreSQLContainer("postgres:16-alpine").withInitScript("db/test-oms-app-login.sql");

  @Test
  void returnLineTracksDamagedQtyAndMixedCondition() throws Exception {
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
        assertThat(rows.getString(1)).isEqualTo("19");
      }
      try (var rows =
          statement.executeQuery(
              """
              SELECT column_name FROM information_schema.columns
              WHERE table_name = 'return_line' AND column_name = 'damaged_qty'
              """)) {
        assertThat(rows.next()).isTrue();
      }
    }
  }
}
