package com.thaishopfun.oms.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class PostgresJdbcUrlTest {

  @Test
  void leavesJdbcUrlsAlone() {
    assertThat(PostgresJdbcUrl.normalize("jdbc:postgresql://localhost:5432/oms")).isNull();
  }

  @Test
  void convertsRailwayUrlAndDecodesPassword() {
    PostgresJdbcUrl.Normalized normalized =
        PostgresJdbcUrl.normalize(
            "postgresql://oms_app:p%40ss@postgres.internal:5432/railway?sslmode=require");

    assertThat(normalized).isNotNull();
    assertThat(normalized.jdbcUrl())
        .isEqualTo("jdbc:postgresql://postgres.internal:5432/railway?sslmode=require");
    assertThat(normalized.username()).isEqualTo("oms_app");
    assertThat(normalized.password()).isEqualTo("p@ss");
  }

  @Test
  void acceptsPostgresSchemeAlias() {
    PostgresJdbcUrl.Normalized normalized =
        PostgresJdbcUrl.normalize("postgres://oms:oms@localhost:5432/oms");

    assertThat(normalized).isNotNull();
    assertThat(normalized.jdbcUrl()).isEqualTo("jdbc:postgresql://localhost:5432/oms");
  }

  @Test
  void rejectsMalformedPostgresUrlWithoutEchoingIt() {
    assertThatThrownBy(() -> PostgresJdbcUrl.normalize("postgresql://not a url"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("DATABASE_URL is not a valid postgres URL")
        .hasMessageNotContaining("not a url");
  }
}
