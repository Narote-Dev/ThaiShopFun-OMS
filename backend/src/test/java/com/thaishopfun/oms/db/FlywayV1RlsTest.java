package com.thaishopfun.oms.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.fail;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Statement;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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
 * V1 is applied by Spring Boot's Flyway on an empty Testcontainers database. The test then enables
 * login on {@code oms_app} and {@code oms_migrator} with passwords that exist only in this class.
 */
@ActiveProfiles("test")
@SpringBootTest
@Testcontainers
class FlywayV1RlsTest {

  private static final String APP_PASSWORD = "oms-app-test-only";
  private static final String MIGRATOR_PASSWORD = "oms-migrator-test-only";

  private static final Set<String> TENANT_TABLES =
      Set.of(
          "tenant",
          "tenant_membership",
          "audit_log",
          "idempotency_key",
          "inbox_event",
          "outbox_event");

  @Container
  static PostgreSQLContainer postgres =
      new PostgreSQLContainer("postgres:16-alpine").withInitScript("db/test-oms-app-login.sql");

  @DynamicPropertySource
  static void runtimeIsOmsApp(DynamicPropertyRegistry registry) {
    // Change: the runtime pool is oms_app. Flyway stays on the container superuser.
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", () -> "oms_app");
    registry.add("spring.datasource.password", () -> APP_PASSWORD);
    registry.add("spring.flyway.url", postgres::getJdbcUrl);
    registry.add("spring.flyway.user", postgres::getUsername);
    registry.add("spring.flyway.password", postgres::getPassword);
    registry.add("oms.inbox.worker-enabled", () -> "false");
  }

  @BeforeEach
  void resetRowsAndEnableTestLogins() throws SQLException {
    // Step 1: Let the test connect as the two non-superuser roles. V1 creates them NOLOGIN.
    try (Connection admin = openAdmin();
        Statement statement = admin.createStatement()) {
      statement.execute("ALTER ROLE oms_app LOGIN PASSWORD '" + APP_PASSWORD + "'");
      statement.execute("ALTER ROLE oms_migrator LOGIN PASSWORD '" + MIGRATOR_PASSWORD + "'");
      // Step 2: Clear rows. The append-only trigger blocks TRUNCATE, including for the superuser.
      // Replica role is the maintenance bypass used only to reset fixtures.
      statement.execute("SET session_replication_role = replica");
      try {
        statement.execute(
            "TRUNCATE TABLE audit_log, idempotency_key, inbox_event, outbox_event, "
                + "tenant_membership, app_user, tenant CASCADE");
      } finally {
        statement.execute("SET session_replication_role = origin");
      }
    }
  }

  @Test
  void migrateOnEmptyDatabasePasses() throws SQLException {
    try (Connection admin = openAdmin()) {
      // Step 1: Flyway recorded a successful V1 against this empty database.
      try (Statement statement = admin.createStatement();
          ResultSet history =
              statement.executeQuery(
                  "SELECT version, success FROM flyway_schema_history ORDER BY installed_rank")) {
        // Change: V2 (JIT), V3 (inbox dedup), and V4 (catalog/stock) are applied with V1.
        java.util.List<String> versions = new java.util.ArrayList<>();
        while (history.next()) {
          assertThat(history.getBoolean("success")).isTrue();
          versions.add(history.getString("version"));
        }
        assertThat(versions).containsExactly("1", "2", "3", "4");
      }

      // Step 2: Every foundation table exists.
      Set<String> tables = new HashSet<>();
      try (Statement statement = admin.createStatement();
          ResultSet rows =
              statement.executeQuery(
                  "SELECT c.relname FROM pg_class c "
                      + "JOIN pg_namespace n ON n.oid = c.relnamespace "
                      + "WHERE n.nspname = 'public' AND c.relkind = 'r'")) {
        while (rows.next()) {
          tables.add(rows.getString(1));
        }
      }
      assertThat(tables)
          .contains(
              "tenant",
              "app_user",
              "tenant_membership",
              "audit_log",
              "idempotency_key",
              "inbox_event",
              "outbox_event");

      // Step 3: Required claim indexes exist.
      Set<String> indexes = new HashSet<>();
      try (Statement statement = admin.createStatement();
          ResultSet rows =
              statement.executeQuery(
                  "SELECT indexname FROM pg_indexes WHERE schemaname = 'public'")) {
        while (rows.next()) {
          indexes.add(rows.getString(1));
        }
      }
      assertThat(indexes)
          .contains(
              "inbox_event_status_next_attempt_at_idx",
              "inbox_event_due_idx",
              "outbox_event_status_next_attempt_at_idx");
    }
  }

  @Test
  void appWithoutTenantContextSeesNoRows() throws SQLException {
    UUID tenantA = UUID.randomUUID();
    UUID tenantB = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    // Step 1: Seed two tenants as the superuser, which bypasses RLS.
    try (Connection admin = openAdmin()) {
      insertTenant(admin, tenantA, shop(tenantA), "ACTIVE");
      insertTenant(admin, tenantB, shop(tenantB), "ACTIVE");
      insertUser(admin, userId);
      insertMembership(admin, UUID.randomUUID(), tenantA, userId);
      insertMembership(admin, UUID.randomUUID(), tenantB, userId);
      insertAudit(admin, UUID.randomUUID(), tenantA);
      insertAudit(admin, UUID.randomUUID(), tenantB);
      insertIdempotency(admin, tenantA, "scope", "a");
      insertIdempotency(admin, tenantB, "scope", "b");
      insertInbox(admin, UUID.randomUUID(), tenantA, "evt-a");
      insertInbox(admin, UUID.randomUUID(), tenantB, "evt-b");
      insertOutbox(admin, UUID.randomUUID(), tenantA);
      insertOutbox(admin, UUID.randomUUID(), tenantB);
    }

    // Step 2: oms_app with no app.tenant_id sees nothing on every tenant table.
    try (Connection app = openApp()) {
      for (String table : TENANT_TABLES) {
        assertThat(count(app, table)).as(table).isZero();
      }
    }
  }

  @Test
  void crossTenantInsertFailsWithCheck() throws SQLException {
    UUID tenantA = UUID.randomUUID();
    UUID tenantB = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    try (Connection admin = openAdmin()) {
      insertTenant(admin, tenantA, shop(tenantA), "ACTIVE");
      insertTenant(admin, tenantB, shop(tenantB), "ACTIVE");
      insertUser(admin, userId);
    }

    try (Connection app = openApp()) {
      // Step 1: Context is tenant A for the whole transaction.
      app.setAutoCommit(false);
      setTenant(app, tenantA.toString());

      // Step 2: A row for tenant B violates WITH CHECK on every tenant table.
      assertRlsViolation(app, () -> insertTenant(app, tenantB, "other-" + tenantB, "ACTIVE"));
      assertRlsViolation(app, () -> insertMembership(app, UUID.randomUUID(), tenantB, userId));
      assertRlsViolation(app, () -> insertAudit(app, UUID.randomUUID(), tenantB));
      assertRlsViolation(app, () -> insertIdempotency(app, tenantB, "scope", "b"));
      assertRlsViolation(app, () -> insertInbox(app, UUID.randomUUID(), tenantB, "evt-b"));
      assertRlsViolation(app, () -> insertOutbox(app, UUID.randomUUID(), tenantB));

      // Step 3: The same statements for tenant A are allowed, and B stays invisible.
      insertMembership(app, UUID.randomUUID(), tenantA, userId);
      insertAudit(app, UUID.randomUUID(), tenantA);
      insertIdempotency(app, tenantA, "scope", "a");
      insertInbox(app, UUID.randomUUID(), tenantA, "evt-a");
      insertOutbox(app, UUID.randomUUID(), tenantA);
      assertThat(count(app, "tenant")).isEqualTo(1);
      assertThat(count(app, "tenant_membership")).isEqualTo(1);
      assertThat(count(app, "audit_log")).isEqualTo(1);
      assertThat(count(app, "idempotency_key")).isEqualTo(1);
      assertThat(count(app, "inbox_event")).isEqualTo(1);
      assertThat(count(app, "outbox_event")).isEqualTo(1);
      app.rollback();
    }
  }

  @Test
  void tableOwnerIsRestrictedByForceRls() throws SQLException {
    UUID tenantA = UUID.randomUUID();
    UUID tenantB = UUID.randomUUID();
    try (Connection admin = openAdmin()) {
      // Step 1: Catalog says FORCE RLS, owner oms_migrator, and both policy clauses.
      // Change: V4 adds catalog/stock policies. FlywayV4CatalogStockTest checks every
      // tenant_id table, so this set must be exactly V1 plus V4.
      Map<String, TablePolicy> policies = loadPolicies(admin);
      Set<String> expected = new HashSet<>(TENANT_TABLES);
      expected.addAll(FlywayV4CatalogStockTest.V4_TABLES);
      assertThat(policies.keySet()).containsExactlyInAnyOrderElementsOf(expected);
      for (String table : TENANT_TABLES) {
        TablePolicy policy = policies.get(table);
        assertThat(policy.owner()).as(table).isEqualTo("oms_migrator");
        assertThat(policy.rowSecurity()).as(table).isTrue();
        assertThat(policy.forceRowSecurity()).as(table).isTrue();
        assertThat(policy.usingExpr()).as(table).contains("app.tenant_id");
        assertThat(policy.checkExpr()).as(table).contains("app.tenant_id");
      }
      assertThat(policies.get("tenant").usingExpr()).contains("id =");
      assertThat(policies.get("inbox_event").usingExpr()).contains("tenant_id =");

      try (Statement statement = admin.createStatement();
          ResultSet appUser =
              statement.executeQuery(
                  "SELECT c.relrowsecurity, pg_get_userbyid(c.relowner) AS owner "
                      + "FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace "
                      + "WHERE n.nspname = 'public' AND c.relname = 'app_user'")) {
        assertThat(appUser.next()).isTrue();
        assertThat(appUser.getBoolean("relrowsecurity")).isFalse();
        assertThat(appUser.getString("owner")).isEqualTo("oms_migrator");
      }

      insertTenant(admin, tenantA, shop(tenantA), "ACTIVE");
      insertTenant(admin, tenantB, shop(tenantB), "ACTIVE");
      insertInbox(admin, UUID.randomUUID(), tenantA, "evt-a");
      insertInbox(admin, UUID.randomUUID(), tenantB, "evt-b");
    }

    // Step 2: The table owner, connected without context, is filtered too.
    try (Connection owner = openMigrator()) {
      assertThat(count(owner, "inbox_event")).isZero();
      assertThat(count(owner, "tenant")).isZero();

      owner.setAutoCommit(false);
      setTenant(owner, tenantA.toString());
      assertThat(count(owner, "inbox_event")).isEqualTo(1);
      assertRlsViolation(owner, () -> insertInbox(owner, UUID.randomUUID(), tenantB, "evt-bad"));
      insertInbox(owner, UUID.randomUUID(), tenantA, "evt-ok");
      assertThat(count(owner, "inbox_event")).isEqualTo(2);
      owner.rollback();
    }
  }

  @Test
  void appRoleHasNoBypassRls() throws SQLException {
    // Step 1: Read pg_roles, which is the acceptance check for NOBYPASSRLS.
    Map<String, RoleFlags> roles = new HashMap<>();
    try (Connection admin = openAdmin();
        Statement statement = admin.createStatement();
        ResultSet rows =
            statement.executeQuery(
                "SELECT rolname, rolsuper, rolbypassrls, rolcanlogin FROM pg_roles "
                    + "WHERE rolname IN ('oms_app', 'oms_migrator', 'oms_maint')")) {
      while (rows.next()) {
        roles.put(
            rows.getString("rolname"),
            new RoleFlags(
                rows.getBoolean("rolsuper"),
                rows.getBoolean("rolbypassrls"),
                rows.getBoolean("rolcanlogin")));
      }
    }

    assertThat(roles.get("oms_app").superuser()).isFalse();
    assertThat(roles.get("oms_app").bypassRls()).isFalse();
    assertThat(roles.get("oms_migrator").superuser()).isFalse();
    assertThat(roles.get("oms_migrator").bypassRls()).isFalse();
    assertThat(roles.get("oms_maint").superuser()).isFalse();
    assertThat(roles.get("oms_maint").bypassRls()).isTrue();
    assertThat(roles.get("oms_maint").canLogin()).isFalse();

    // Step 2: oms_app can write tenant tables and cannot read app_user or mutate audit_log.
    try (Connection admin = openAdmin();
        Statement statement = admin.createStatement();
        ResultSet privileges =
            statement.executeQuery(
                "SELECT "
                    + "has_table_privilege('oms_app', 'public.inbox_event', 'SELECT') AS inbox_read, "
                    + "has_table_privilege('oms_app', 'public.inbox_event', 'INSERT') AS inbox_write, "
                    + "has_table_privilege('oms_app', 'public.audit_log', 'INSERT') AS audit_insert, "
                    + "has_table_privilege('oms_app', 'public.audit_log', 'UPDATE') AS audit_update, "
                    + "has_table_privilege('oms_app', 'public.audit_log', 'DELETE') AS audit_delete, "
                    + "has_table_privilege('oms_app', 'public.app_user', 'SELECT') AS user_read, "
                    + "has_table_privilege('oms_app', 'public.app_user', 'INSERT') AS user_write, "
                    + "has_table_privilege('oms_app', 'public.tenant', 'DELETE') AS tenant_delete")) {
      assertThat(privileges.next()).isTrue();
      assertThat(privileges.getBoolean("inbox_read")).isTrue();
      assertThat(privileges.getBoolean("inbox_write")).isTrue();
      assertThat(privileges.getBoolean("audit_insert")).isTrue();
      assertThat(privileges.getBoolean("audit_update")).isFalse();
      assertThat(privileges.getBoolean("audit_delete")).isFalse();
      assertThat(privileges.getBoolean("user_read")).isFalse();
      assertThat(privileges.getBoolean("user_write")).isFalse();
      assertThat(privileges.getBoolean("tenant_delete")).isFalse();
    }
  }

  @Test
  void securityDefinerFunctionsReturnOnlyIds() throws SQLException {
    UUID active = UUID.randomUUID();
    UUID grace = UUID.randomUUID();
    UUID suspended = UUID.randomUUID();
    UUID inboxId = UUID.randomUUID();
    UUID outboxId = UUID.randomUUID();

    try (Connection admin = openAdmin()) {
      // Step 1: The four functions are SECURITY DEFINER, owned by oms_maint, id-only.
      Map<String, FunctionShape> functions = new HashMap<>();
      try (Statement statement = admin.createStatement();
          ResultSet rows =
              statement.executeQuery(
                  "SELECT p.proname, pg_get_function_identity_arguments(p.oid) AS args, "
                      + "pg_get_function_result(p.oid) AS result, p.prosecdef, r.rolname AS owner "
                      + "FROM pg_proc p "
                      + "JOIN pg_namespace n ON n.oid = p.pronamespace "
                      + "JOIN pg_roles r ON r.oid = p.proowner "
                      + "WHERE n.nspname = 'public' AND p.proname IN ("
                      + "'list_active_tenant_ids', 'resolve_tenant', "
                      + "'claim_inbox_batch', 'claim_outbox_batch')")) {
        while (rows.next()) {
          functions.put(
              rows.getString("proname"),
              new FunctionShape(
                  rows.getString("args"),
                  rows.getString("result"),
                  rows.getBoolean("prosecdef"),
                  rows.getString("owner")));
        }
      }
      assertThat(functions.get("list_active_tenant_ids"))
          .isEqualTo(new FunctionShape("", "TABLE(id uuid)", true, "oms_maint"));
      assertThat(functions.get("resolve_tenant"))
          .isEqualTo(
              new FunctionShape("channel text, external_shop_id text", "uuid", true, "oms_maint"));
      assertThat(functions.get("claim_inbox_batch"))
          .isEqualTo(
              new FunctionShape(
                  "n integer, p_lease interval",
                  "TABLE(id uuid, tenant_id uuid, next_attempt_at timestamp with time zone)",
                  true,
                  "oms_maint"));
      assertThat(functions.get("claim_outbox_batch"))
          .isEqualTo(
              new FunctionShape(
                  "n integer, p_lease interval",
                  "TABLE(id uuid, tenant_id uuid)",
                  true,
                  "oms_maint"));

      insertTenant(admin, active, "shop-active", "ACTIVE");
      insertTenant(admin, grace, "shop-grace", "GRACE");
      insertTenant(admin, suspended, "shop-suspended", "SUSPENDED");
      insertInbox(admin, inboxId, active, "evt-active");
      insertOutbox(admin, outboxId, grace);
    }

    try (Connection app = openApp()) {
      // Step 2: list and resolve return uuids and no other column.
      Set<UUID> activeIds = new HashSet<>();
      try (Statement statement = app.createStatement();
          ResultSet rows = statement.executeQuery("SELECT id FROM list_active_tenant_ids()")) {
        assertUuidColumn(rows, 1);
        while (rows.next()) {
          activeIds.add(rows.getObject(1, UUID.class));
        }
      }
      assertThat(activeIds).containsExactly(active);

      try (PreparedStatement statement = app.prepareStatement("SELECT resolve_tenant(?, ?)")) {
        statement.setString(1, "TSF");
        statement.setString(2, "shop-active");
        try (ResultSet rows = statement.executeQuery()) {
          assertUuidColumn(rows, 1);
          assertThat(rows.next()).isTrue();
          assertThat(rows.getObject(1, UUID.class)).isEqualTo(active);
        }
        statement.setString(1, "SHOPEE");
        statement.setString(2, "shop-active");
        try (ResultSet rows = statement.executeQuery()) {
          assertUuidColumn(rows, 1);
          assertThat(rows.next()).isTrue();
          assertThat(rows.getObject(1, UUID.class)).isNull();
        }
      }

      // Step 3: Claims return id and tenant_id. A bad limit or lease is rejected.
      assertThatThrownBy(() -> claimInbox(app, 0))
          .isInstanceOf(PSQLException.class)
          .hasMessageContaining("between 1 and 1000");
      assertThatThrownBy(() -> claimInbox(app, 1, "2 hours"))
          .isInstanceOf(PSQLException.class)
          .hasMessageContaining("at most 1 hour");
      Claimed claimedInbox = claimInbox(app, 10);
      Claimed claimedOutbox = claimOutbox(app, 10);
      assertThat(claimedInbox).isEqualTo(new Claimed(inboxId, active));
      assertThat(claimedOutbox).isEqualTo(new Claimed(outboxId, grace));
    }

    try (Connection admin = openAdmin();
        Statement statement = admin.createStatement();
        ResultSet outbox =
            statement.executeQuery(
                "SELECT status, attempts, lease_until IS NOT NULL AS leased "
                    + "FROM outbox_event WHERE id = '"
                    + outboxId
                    + "'")) {
      assertThat(outbox.next()).isTrue();
      assertThat(outbox.getString("status")).isEqualTo("IN_FLIGHT");
      assertThat(outbox.getInt("attempts")).isEqualTo(1);
      assertThat(outbox.getBoolean("leased")).isTrue();
    }
  }

  @Test
  void claimBatchesSkipLockedRows() throws SQLException {
    UUID tenant = UUID.randomUUID();
    UUID first = UUID.randomUUID();
    UUID second = UUID.randomUUID();
    UUID expired = UUID.randomUUID();
    try (Connection admin = openAdmin()) {
      insertTenant(admin, tenant, shop(tenant), "ACTIVE");
      insertInbox(admin, first, tenant, "evt-1");
      insertInbox(admin, second, tenant, "evt-2");
      insertOutbox(admin, expired, tenant);
    }

    // Step 1: Two open transactions each claim one inbox row. Neither waits on the other.
    try (Connection left = openApp();
        Connection right = openApp()) {
      left.setAutoCommit(false);
      right.setAutoCommit(false);
      try (Statement statement = right.createStatement()) {
        statement.execute("SET statement_timeout = '3s'");
      }
      Claimed leftClaim = claimInbox(left, 1);
      Claimed rightClaim = claimInbox(right, 1);
      assertThat(Set.of(leftClaim.id(), rightClaim.id())).containsExactlyInAnyOrder(first, second);
      assertThat(leftClaim.tenantId()).isEqualTo(tenant);
      assertThat(rightClaim.tenantId()).isEqualTo(tenant);
      left.commit();
      right.commit();
    }

    // Step 2: The inbox lease hides those rows. A live outbox lease does too.
    try (Connection app = openApp()) {
      try (PreparedStatement inbox =
          app.prepareStatement("SELECT id, tenant_id FROM claim_inbox_batch(10)")) {
        try (ResultSet rows = inbox.executeQuery()) {
          assertThat(rows.next()).isFalse();
        }
      }
      assertThat(claimOutbox(app, 10)).isEqualTo(new Claimed(expired, tenant));
      try (PreparedStatement again =
          app.prepareStatement("SELECT id, tenant_id FROM claim_outbox_batch(10)")) {
        try (ResultSet rows = again.executeQuery()) {
          assertThat(rows.next()).isFalse();
        }
      }
    }

    // Step 3: A lease that has expired can be claimed again.
    try (Connection admin = openAdmin();
        Statement statement = admin.createStatement()) {
      statement.execute(
          "UPDATE outbox_event SET lease_until = now() - interval '1 minute' WHERE id = '"
              + expired
              + "'");
    }
    try (Connection app = openApp()) {
      assertThat(claimOutbox(app, 10)).isEqualTo(new Claimed(expired, tenant));
    }
    try (Connection admin = openAdmin();
        Statement statement = admin.createStatement();
        ResultSet attempts =
            statement.executeQuery(
                "SELECT attempts FROM outbox_event WHERE id = '" + expired + "'")) {
      assertThat(attempts.next()).isTrue();
      assertThat(attempts.getInt(1)).isEqualTo(2);
    }
  }

  @Test
  void tenantContextIsTransactionScoped() throws SQLException {
    UUID tenant = UUID.randomUUID();
    try (Connection admin = openAdmin()) {
      insertTenant(admin, tenant, shop(tenant), "ACTIVE");
      insertInbox(admin, UUID.randomUUID(), tenant, "evt");
    }

    try (Connection app = openApp()) {
      // Step 1: set_config(..., true) is visible only until commit or rollback.
      app.setAutoCommit(false);
      assertThat(count(app, "inbox_event")).isZero();
      setTenant(app, tenant.toString());
      assertThat(count(app, "inbox_event")).isEqualTo(1);
      app.commit();
      assertThat(currentTenantSetting(app)).isBlank();
      assertThat(count(app, "inbox_event")).isZero();

      setTenant(app, tenant.toString());
      assertThat(count(app, "inbox_event")).isEqualTo(1);
      app.rollback();
      assertThat(currentTenantSetting(app)).isBlank();
      assertThat(count(app, "inbox_event")).isZero();

      // Step 2: A blank setting matches nothing. A non-uuid value fails closed.
      setTenant(app, "");
      assertThat(count(app, "inbox_event")).isZero();
      app.rollback();
      setTenant(app, "not-a-uuid");
      assertThatThrownBy(() -> count(app, "inbox_event")).isInstanceOf(PSQLException.class);
      app.rollback();
    }
  }

  @Test
  void definerFunctionsAreExecutableOnlyByApp() throws SQLException {
    // Step 1: Each SECURITY DEFINER function pins search_path to pg_catalog, pg_temp.
    Set<String> names = new HashSet<>();
    try (Connection admin = openAdmin();
        Statement statement = admin.createStatement();
        ResultSet rows =
            statement.executeQuery(
                "SELECT p.proname, p.proconfig::text AS config "
                    + "FROM pg_proc p "
                    + "JOIN pg_namespace n ON n.oid = p.pronamespace "
                    + "WHERE n.nspname = 'public' AND p.proname IN ("
                    + "'list_active_tenant_ids', 'resolve_tenant', "
                    + "'claim_inbox_batch', 'claim_outbox_batch')")) {
      while (rows.next()) {
        names.add(rows.getString("proname"));
        assertThat(rows.getString("config")).contains("search_path=pg_catalog, pg_temp");
      }
    }
    assertThat(names)
        .containsExactlyInAnyOrder(
            "list_active_tenant_ids", "resolve_tenant", "claim_inbox_batch", "claim_outbox_batch");

    // Step 2: oms_app may execute them. oms_migrator is denied.
    try (Connection admin = openAdmin();
        Statement statement = admin.createStatement();
        ResultSet privileges =
            statement.executeQuery(
                "SELECT "
                    + "has_function_privilege('oms_app', 'public.list_active_tenant_ids()', 'EXECUTE') AS app_list, "
                    + "has_function_privilege('oms_migrator', 'public.list_active_tenant_ids()', 'EXECUTE') AS mig_list, "
                    + "has_function_privilege('oms_app', 'public.resolve_tenant(text, text)', 'EXECUTE') AS app_resolve, "
                    + "has_function_privilege('oms_migrator', 'public.resolve_tenant(text, text)', 'EXECUTE') AS mig_resolve, "
                    + "has_function_privilege('oms_app', 'public.claim_inbox_batch(integer, interval)', 'EXECUTE') AS app_inbox, "
                    + "has_function_privilege('oms_migrator', 'public.claim_inbox_batch(integer, interval)', 'EXECUTE') AS mig_inbox, "
                    + "has_function_privilege('oms_app', 'public.claim_outbox_batch(integer, interval)', 'EXECUTE') AS app_outbox, "
                    + "has_function_privilege('oms_migrator', 'public.claim_outbox_batch(integer, interval)', 'EXECUTE') AS mig_outbox")) {
      assertThat(privileges.next()).isTrue();
      assertThat(privileges.getBoolean("app_list")).isTrue();
      assertThat(privileges.getBoolean("mig_list")).isFalse();
      assertThat(privileges.getBoolean("app_resolve")).isTrue();
      assertThat(privileges.getBoolean("mig_resolve")).isFalse();
      assertThat(privileges.getBoolean("app_inbox")).isTrue();
      assertThat(privileges.getBoolean("mig_inbox")).isFalse();
      assertThat(privileges.getBoolean("app_outbox")).isTrue();
      assertThat(privileges.getBoolean("mig_outbox")).isFalse();
    }

    try (Connection migrator = openMigrator();
        Statement statement = migrator.createStatement()) {
      assertThatThrownBy(() -> statement.executeQuery("SELECT id FROM list_active_tenant_ids()"))
          .isInstanceOf(PSQLException.class)
          .hasMessageContaining("permission denied");
      assertThatThrownBy(
              () -> statement.executeQuery("SELECT id, tenant_id FROM claim_outbox_batch(1)"))
          .isInstanceOf(PSQLException.class)
          .hasMessageContaining("permission denied");
    }
  }

  @Test
  void auditLogIsAppendOnly() throws SQLException {
    UUID tenant = UUID.randomUUID();
    try (Connection admin = openAdmin()) {
      insertTenant(admin, tenant, shop(tenant), "ACTIVE");
      insertAudit(admin, UUID.randomUUID(), tenant);
      assertThatThrownBy(
              () -> {
                try (Statement statement = admin.createStatement()) {
                  statement.executeUpdate("UPDATE audit_log SET action = 'changed'");
                }
              })
          .isInstanceOf(PSQLException.class)
          .hasMessageContaining("append-only");
      assertThatThrownBy(
              () -> {
                try (Statement statement = admin.createStatement()) {
                  statement.executeUpdate("DELETE FROM audit_log");
                }
              })
          .isInstanceOf(PSQLException.class)
          .hasMessageContaining("append-only");
      assertThatThrownBy(
              () -> {
                try (Statement statement = admin.createStatement()) {
                  statement.execute("TRUNCATE TABLE audit_log");
                }
              })
          .isInstanceOf(PSQLException.class)
          .hasMessageContaining("append-only");
      assertThat(count(admin, "audit_log")).isEqualTo(1);
    }
  }

  @Test
  void disablingRowSecurityDoesNotRevealRows() throws SQLException {
    UUID tenant = UUID.randomUUID();
    try (Connection admin = openAdmin()) {
      insertTenant(admin, tenant, shop(tenant), "ACTIVE");
      insertInbox(admin, UUID.randomUUID(), tenant, "evt");
    }

    // Step 1: oms_app cannot turn RLS off. Postgres errors instead of returning the row.
    try (Connection app = openApp();
        Statement statement = app.createStatement()) {
      statement.execute("SET row_security = off");
      assertThatThrownBy(() -> count(app, "inbox_event"))
          .isInstanceOf(PSQLException.class)
          .hasMessageContaining("row-level security");
    }
  }

  private Connection openAdmin() throws SQLException {
    return DriverManager.getConnection(
        postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
  }

  private Connection openApp() throws SQLException {
    return DriverManager.getConnection(postgres.getJdbcUrl(), "oms_app", APP_PASSWORD);
  }

  private Connection openMigrator() throws SQLException {
    return DriverManager.getConnection(postgres.getJdbcUrl(), "oms_migrator", MIGRATOR_PASSWORD);
  }

  private static void setTenant(Connection connection, String tenantId) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement("SELECT set_config('app.tenant_id', ?, true)")) {
      statement.setString(1, tenantId);
      statement.execute();
    }
  }

  private static String currentTenantSetting(Connection connection) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet rows = statement.executeQuery("SELECT current_setting('app.tenant_id', true)")) {
      assertThat(rows.next()).isTrue();
      return rows.getString(1);
    }
  }

  private static long count(Connection connection, String table) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet rows = statement.executeQuery("SELECT count(*) FROM " + table)) {
      assertThat(rows.next()).isTrue();
      return rows.getLong(1);
    }
  }

  private static void assertRlsViolation(Connection connection, SqlAction action)
      throws SQLException {
    Savepoint savepoint = connection.setSavepoint();
    try {
      action.run();
      fail("expected row-level security violation");
    } catch (PSQLException exception) {
      assertThat(exception.getSQLState()).isEqualTo("42501");
      assertThat(exception.getMessage()).containsIgnoringCase("row-level security");
    } finally {
      connection.rollback(savepoint);
    }
  }

  private static void assertUuidColumn(ResultSet rows, int columns) throws SQLException {
    assertThat(rows.getMetaData().getColumnCount()).isEqualTo(columns);
    for (int column = 1; column <= columns; column++) {
      assertThat(rows.getMetaData().getColumnTypeName(column)).isEqualToIgnoringCase("uuid");
    }
  }

  private static Claimed claimInbox(Connection connection, int limit) throws SQLException {
    return claimInbox(connection, limit, null);
  }

  private static Claimed claimInbox(Connection connection, int limit, String lease)
      throws SQLException {
    String sql =
        lease == null
            ? "SELECT id, tenant_id FROM claim_inbox_batch(?)"
            : "SELECT id, tenant_id FROM claim_inbox_batch(?, ?::interval)";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setInt(1, limit);
      if (lease != null) {
        statement.setString(2, lease);
      }
      try (ResultSet rows = statement.executeQuery()) {
        assertUuidColumn(rows, 2);
        assertThat(rows.next()).isTrue();
        Claimed claimed = new Claimed(rows.getObject(1, UUID.class), rows.getObject(2, UUID.class));
        assertThat(rows.next()).isFalse();
        return claimed;
      }
    }
  }

  private static Claimed claimOutbox(Connection connection, int limit) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement("SELECT id, tenant_id FROM claim_outbox_batch(?)")) {
      statement.setInt(1, limit);
      try (ResultSet rows = statement.executeQuery()) {
        assertUuidColumn(rows, 2);
        assertThat(rows.next()).isTrue();
        Claimed claimed = new Claimed(rows.getObject(1, UUID.class), rows.getObject(2, UUID.class));
        assertThat(rows.next()).isFalse();
        return claimed;
      }
    }
  }

  private static Map<String, TablePolicy> loadPolicies(Connection admin) throws SQLException {
    Map<String, TablePolicy> policies = new HashMap<>();
    try (Statement statement = admin.createStatement();
        ResultSet rows =
            statement.executeQuery(
                "SELECT c.relname, c.relrowsecurity, c.relforcerowsecurity, "
                    + "pg_get_userbyid(c.relowner) AS owner, "
                    + "pg_get_expr(p.polqual, p.polrelid) AS using_expr, "
                    + "pg_get_expr(p.polwithcheck, p.polrelid) AS check_expr "
                    + "FROM pg_class c "
                    + "JOIN pg_namespace n ON n.oid = c.relnamespace "
                    + "JOIN pg_policy p ON p.polrelid = c.oid "
                    + "WHERE n.nspname = 'public' AND c.relkind = 'r'")) {
      while (rows.next()) {
        policies.put(
            rows.getString("relname"),
            new TablePolicy(
                rows.getString("owner"),
                rows.getBoolean("relrowsecurity"),
                rows.getBoolean("relforcerowsecurity"),
                rows.getString("using_expr"),
                rows.getString("check_expr")));
      }
    }
    return policies;
  }

  private static void insertTenant(Connection connection, UUID id, String shopId, String status)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO tenant (id, name, tsf_shop_id, membership_tier, entitlement_status, ent_ver) "
                + "VALUES (?, 'Shop', ?, 'PRO', ?, 1)")) {
      statement.setObject(1, id);
      statement.setString(2, shopId);
      statement.setString(3, status);
      statement.executeUpdate();
    }
  }

  private static void insertUser(Connection connection, UUID id) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO app_user (id, tsf_user_id, email) VALUES (?, ?, 'user@example.com')")) {
      statement.setObject(1, id);
      statement.setString(2, "user-" + id);
      statement.executeUpdate();
    }
  }

  private static void insertMembership(Connection connection, UUID id, UUID tenantId, UUID userId)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO tenant_membership (id, tenant_id, user_id, role, status) "
                + "VALUES (?, ?, ?, 'STAFF', 'ACTIVE')")) {
      statement.setObject(1, id);
      statement.setObject(2, tenantId);
      statement.setObject(3, userId);
      statement.executeUpdate();
    }
  }

  private static void insertAudit(Connection connection, UUID id, UUID tenantId)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO audit_log (id, tenant_id, actor_type, action, entity_type) "
                + "VALUES (?, ?, 'SYSTEM', 'test', 'tenant')")) {
      statement.setObject(1, id);
      statement.setObject(2, tenantId);
      statement.executeUpdate();
    }
  }

  private static void insertIdempotency(
      Connection connection, UUID tenantId, String scope, String key) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO idempotency_key (tenant_id, scope, \"key\", request_hash) "
                + "VALUES (?, ?, ?, 'hash')")) {
      statement.setObject(1, tenantId);
      statement.setString(2, scope);
      statement.setString(3, key);
      statement.executeUpdate();
    }
  }

  private static void insertInbox(Connection connection, UUID id, UUID tenantId, String eventId)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO inbox_event "
                + "(id, tenant_id, source, event_id, event_type, aggregate_id, payload, status) "
                + "VALUES (?, ?, 'TSF', ?, 'order.created', 'agg', '{}'::jsonb, 'RECEIVED')")) {
      statement.setObject(1, id);
      statement.setObject(2, tenantId);
      statement.setString(3, eventId);
      statement.executeUpdate();
    }
  }

  private static void insertOutbox(Connection connection, UUID id, UUID tenantId)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO outbox_event "
                + "(id, tenant_id, aggregate_type, aggregate_id, event_type, payload, status) "
                + "VALUES (?, ?, 'order', 'agg', 'stock.updated', '{}'::jsonb, 'PENDING')")) {
      statement.setObject(1, id);
      statement.setObject(2, tenantId);
      statement.executeUpdate();
    }
  }

  private static String shop(UUID tenantId) {
    return "shop-" + tenantId;
  }

  @FunctionalInterface
  private interface SqlAction {
    void run() throws SQLException;
  }

  private record RoleFlags(boolean superuser, boolean bypassRls, boolean canLogin) {}

  private record FunctionShape(String args, String result, boolean securityDefiner, String owner) {}

  private record Claimed(UUID id, UUID tenantId) {}

  private record TablePolicy(
      String owner,
      boolean rowSecurity,
      boolean forceRowSecurity,
      String usingExpr,
      String checkExpr) {}
}
