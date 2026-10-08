package com.thaishopfun.oms.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.thaishopfun.oms.auth.AuthTestSupport;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
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
class FlywayV14OrderBackfillTest {

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    AuthTestSupport.register(registry);
  }

  @Container
  static PostgreSQLContainer postgres =
      new PostgreSQLContainer("postgres:16-alpine").withInitScript("db/test-oms-app-login.sql");

  @Test
  void listTenantsForOrderBackfillIsDefinerAndGrantedToApp() throws Exception {
    try (Connection conn = AuthTestSupport.admin()) {
      try (Statement statement = conn.createStatement();
          ResultSet rows =
              statement.executeQuery(
                  """
                  SELECT p.prosecdef, r.rolname AS owner,
                    has_function_privilege('oms_app', 'public.list_tenants_for_order_backfill()', 'EXECUTE') AS app
                  FROM pg_proc p
                  JOIN pg_roles r ON r.oid = p.proowner
                  WHERE p.proname = 'list_tenants_for_order_backfill'
                  """)) {
        assertThat(rows.next()).isTrue();
        assertThat(rows.getBoolean("prosecdef")).isTrue();
        assertThat(rows.getString("owner")).isEqualTo("oms_maint");
        assertThat(rows.getBoolean("app")).isTrue();
      }
      try (Statement statement = conn.createStatement();
          ResultSet rows =
              statement.executeQuery("SELECT id FROM list_tenants_for_order_backfill()")) {
        assertThat(rows.next()).isFalse();
      }
    }
  }
}
