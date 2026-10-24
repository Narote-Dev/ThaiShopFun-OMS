package com.thaishopfun.oms.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.thaishopfun.oms.auth.AuthTestSupport;
import com.thaishopfun.oms.invariant.VerifyInvariants;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest
@ActiveProfiles("test")
@VerifyInvariants
class FlywayV16PiiLifecycleTest {
  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    AuthTestSupport.register(registry);
  }

  @Test
  void discoveryReturnsOnlyTenantIdsAndAppWithoutContextSeesNoRows() throws Exception {
    try (var app = AuthTestSupport.app();
        var s = app.createStatement()) {
      try (var rows = s.executeQuery("SELECT * FROM list_tenants_with_pii_due(now())")) {
        assertThat(rows.getMetaData().getColumnCount()).isEqualTo(1);
        assertThat(rows.getMetaData().getColumnName(1)).isEqualTo("tenant_id");
        assertThat(rows.getMetaData().getColumnTypeName(1)).isEqualTo("uuid");
      }
      for (String table : new String[] {"order_recipient", "inbox_event", "outbox_event"}) {
        try (var rows = s.executeQuery("SELECT count(*) FROM " + table)) {
          rows.next();
          assertThat(rows.getInt(1)).isZero();
        }
      }
      try (var rows =
          s.executeQuery(
              """
          SELECT p.prosecdef, pg_get_userbyid(p.proowner), p.proconfig::text,
            has_function_privilege('oms_app', p.oid, 'EXECUTE'),
            EXISTS (SELECT 1 FROM aclexplode(p.proacl) a WHERE a.grantee = 0 AND a.privilege_type = 'EXECUTE')
          FROM pg_proc p WHERE p.proname = 'list_tenants_with_pii_due'
          """)) {
        assertThat(rows.next()).isTrue();
        assertThat(rows.getBoolean(1)).isTrue();
        assertThat(rows.getString(2)).isEqualTo("oms_maint");
        assertThat(rows.getString(3)).contains("pg_catalog, pg_temp");
        assertThat(rows.getBoolean(4)).isTrue();
        assertThat(rows.getBoolean(5)).isFalse();
      }
      try (var rows =
          s.executeQuery(
              "SELECT bool_and(relrowsecurity AND relforcerowsecurity) FROM pg_class WHERE oid IN ('order_recipient'::regclass, 'inbox_event'::regclass, 'outbox_event'::regclass)")) {
        rows.next();
        assertThat(rows.getBoolean(1)).isTrue();
      }
    }
  }
}
