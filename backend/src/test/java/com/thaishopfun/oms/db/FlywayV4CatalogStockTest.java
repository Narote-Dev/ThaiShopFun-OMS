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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * V4 catalog, warehouse, and stock schema. Constraints and triggers are exercised as {@code
 * oms_app} (NOBYPASSRLS, not an owner) unless a case is about the owner or the superuser.
 */
@ActiveProfiles("test")
@SpringBootTest
@Testcontainers
class FlywayV4CatalogStockTest {

  private static final String APP_PASSWORD = "oms-app-test-only";
  private static final String MIGRATOR_PASSWORD = "oms-migrator-test-only";

  static final Set<String> V4_TABLES =
      Set.of(
          "channel_account",
          "product",
          "sku",
          "sku_bundle_component",
          "channel_listing",
          "warehouse",
          "inventory",
          "inventory_ledger",
          "stock_reservation",
          "stock_document",
          "stock_document_line");

  /** Rows one {@link #seed} call creates per table. */
  private static final Map<String, Integer> ROWS_PER_GRAPH =
      Map.ofEntries(
          Map.entry("channel_account", 1),
          Map.entry("product", 1),
          Map.entry("sku", 3),
          Map.entry("sku_bundle_component", 1),
          Map.entry("channel_listing", 1),
          Map.entry("warehouse", 1),
          Map.entry("inventory", 1),
          Map.entry("inventory_ledger", 1),
          Map.entry("stock_reservation", 1),
          Map.entry("stock_document", 1),
          Map.entry("stock_document_line", 1));

  private static final List<String> TRIGGER_FUNCTIONS =
      List.of(
          "sku_bundle_component_check",
          "sku_require_stockable",
          "sku_is_bundle_change_check",
          "inventory_ledger_reject_mutation",
          "stock_document_guard",
          "stock_document_line_guard");

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
  void resetRowsAndEnableTestLogins() throws SQLException {
    try (Connection admin = openAdmin();
        Statement statement = admin.createStatement()) {
      // Step 1: Let the test connect as the two non-superuser roles.
      statement.execute("ALTER ROLE oms_app LOGIN PASSWORD '" + APP_PASSWORD + "'");
      statement.execute("ALTER ROLE oms_migrator LOGIN PASSWORD '" + MIGRATOR_PASSWORD + "'");
      // Step 2: Clear rows. Replica role skips the append-only and immutability triggers.
      statement.execute("SET session_replication_role = replica");
      try {
        statement.execute(
            "TRUNCATE TABLE "
                + String.join(", ", V4_TABLES)
                + ", audit_log, idempotency_key, inbox_event, outbox_event, "
                + "tenant_membership, app_user, tenant CASCADE");
      } finally {
        statement.execute("SET session_replication_role = origin");
      }
    }
  }

  @Test
  void everyTenantTableHasForcedTenantIsolation() throws SQLException {
    // Step 1: Find every public table with a tenant_id column. Future migrations are covered.
    Map<String, TableSecurity> tables = new LinkedHashMap<>();
    try (Connection admin = openAdmin();
        Statement statement = admin.createStatement();
        ResultSet rows =
            statement.executeQuery(
                "SELECT c.relname, c.relrowsecurity, c.relforcerowsecurity, "
                    + "pg_get_userbyid(c.relowner) AS owner, "
                    + "(SELECT count(*) FROM pg_policy p WHERE p.polrelid = c.oid) AS policies, "
                    + "(SELECT p.polcmd::text || '|' || p.polpermissive::text || '|' "
                    + "   || p.polroles::text || '|' || pg_get_expr(p.polqual, p.polrelid) "
                    + "   || '|' || pg_get_expr(p.polwithcheck, p.polrelid) "
                    + " FROM pg_policy p "
                    + " WHERE p.polrelid = c.oid AND p.polname = 'tenant_isolation') AS policy "
                    + "FROM pg_class c "
                    + "JOIN pg_namespace n ON n.oid = c.relnamespace "
                    + "WHERE n.nspname = 'public' AND c.relkind IN ('r', 'p') "
                    + "AND EXISTS (SELECT 1 FROM pg_attribute a WHERE a.attrelid = c.oid "
                    + "  AND a.attname = 'tenant_id' AND NOT a.attisdropped)")) {
      while (rows.next()) {
        tables.put(
            rows.getString("relname"),
            new TableSecurity(
                rows.getBoolean("relrowsecurity"),
                rows.getBoolean("relforcerowsecurity"),
                rows.getString("owner"),
                rows.getInt("policies"),
                rows.getString("policy")));
      }
    }

    // Step 2: Each one is RLS + FORCE, owned by oms_migrator, with exactly one ALL policy
    // for PUBLIC that matches tenant_id on both USING and WITH CHECK.
    assertThat(tables.keySet()).containsAll(V4_TABLES);
    for (Map.Entry<String, TableSecurity> entry : tables.entrySet()) {
      String table = entry.getKey();
      TableSecurity security = entry.getValue();
      assertThat(security.rowSecurity()).as(table).isTrue();
      assertThat(security.forceRowSecurity()).as(table).isTrue();
      assertThat(security.owner()).as(table).isNotEqualTo("oms_app").isEqualTo("oms_migrator");
      assertThat(security.policies()).as(table).isEqualTo(1);
      assertThat(security.policy()).as(table).isNotNull();
      String[] parts = security.policy().split("\\|", -1);
      assertThat(parts[0]).as(table + " polcmd").isEqualTo("*");
      assertThat(parts[1]).as(table + " permissive").isEqualTo("true");
      assertThat(parts[2]).as(table + " roles").isEqualTo("{0}");
      for (String expr : List.of(parts[3], parts[4])) {
        assertThat(expr).as(table).contains("tenant_id =").contains("app.tenant_id");
      }
    }
  }

  @Test
  void grantsAndTriggerFunctionsAreLocked() throws SQLException {
    try (Connection admin = openAdmin()) {
      // Step 1: oms_app has DML on every V4 table, only SELECT/INSERT on the ledger, no TRUNCATE.
      for (String table : V4_TABLES) {
        boolean ledger = table.equals("inventory_ledger");
        assertThat(tablePrivilege(admin, "oms_app", table, "SELECT")).as(table).isTrue();
        assertThat(tablePrivilege(admin, "oms_app", table, "INSERT")).as(table).isTrue();
        assertThat(tablePrivilege(admin, "oms_app", table, "UPDATE")).as(table).isEqualTo(!ledger);
        assertThat(tablePrivilege(admin, "oms_app", table, "DELETE")).as(table).isEqualTo(!ledger);
        assertThat(tablePrivilege(admin, "oms_app", table, "TRUNCATE")).as(table).isFalse();
        assertThat(tablePrivilege(admin, "oms_maint", table, "SELECT")).as(table).isTrue();
        assertThat(tablePrivilege(admin, "oms_maint", table, "UPDATE"))
            .as(table)
            .isEqualTo(!ledger);
      }

      // Step 2: Nothing on the V4 tables is granted to PUBLIC.
      try (PreparedStatement statement =
          admin.prepareStatement(
              "SELECT count(*) FROM pg_class c "
                  + "JOIN pg_namespace n ON n.oid = c.relnamespace, "
                  + "LATERAL aclexplode(c.relacl) AS a "
                  + "WHERE n.nspname = 'public' AND c.relname = ANY (?) AND a.grantee = 0")) {
        statement.setArray(1, admin.createArrayOf("text", V4_TABLES.toArray()));
        assertThat(single(statement)).isZero();
      }

      // Step 3: Trigger functions are owned by oms_migrator, pin search_path, and PUBLIC
      // cannot execute them.
      try (PreparedStatement statement =
          admin.prepareStatement(
              "SELECT p.proname, pg_get_userbyid(p.proowner) AS owner, p.proconfig::text AS config, "
                  + "p.prosecdef, "
                  + "EXISTS (SELECT 1 FROM aclexplode(p.proacl) AS a WHERE a.grantee = 0) "
                  + "  OR p.proacl IS NULL AS public_exec "
                  + "FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace "
                  + "WHERE n.nspname = 'public' AND p.proname = ANY (?)")) {
        statement.setArray(1, admin.createArrayOf("text", TRIGGER_FUNCTIONS.toArray()));
        Set<String> seen = new HashSet<>();
        try (ResultSet rows = statement.executeQuery()) {
          while (rows.next()) {
            String name = rows.getString("proname");
            seen.add(name);
            assertThat(rows.getString("owner")).as(name).isEqualTo("oms_migrator");
            assertThat(rows.getString("config"))
                .as(name)
                .contains("search_path=pg_catalog, public");
            assertThat(rows.getBoolean("prosecdef")).as(name).isFalse();
            assertThat(rows.getBoolean("public_exec")).as(name).isFalse();
          }
        }
        assertThat(seen).containsExactlyInAnyOrderElementsOf(TRIGGER_FUNCTIONS);
      }
    }
  }

  @Test
  void migrationAppliesOnTopOfV3WithExistingData() throws SQLException {
    String database = "oms_upgrade_" + UUID.randomUUID().toString().replace("-", "");
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
    try {
      // Step 1: Stop at V3 and leave a tenant with inbox data behind.
      Flyway.configure()
          .dataSource(url, postgres.getUsername(), postgres.getPassword())
          .target("3")
          .load()
          .migrate();
      UUID tenant = UUID.randomUUID();
      try (Connection upgrade =
          DriverManager.getConnection(url, postgres.getUsername(), postgres.getPassword())) {
        insertTenant(upgrade, tenant);
        try (PreparedStatement statement =
            upgrade.prepareStatement(
                "INSERT INTO inbox_event "
                    + "(id, tenant_id, source, event_id, event_type, aggregate_id, payload, status) "
                    + "VALUES (?, ?, 'TSF', 'evt', 'order.created', 'agg', '{}'::jsonb, 'RECEIVED')")) {
          statement.setObject(1, UUID.randomUUID());
          statement.setObject(2, tenant);
          statement.executeUpdate();
        }
      }

      // Step 2: Apply V4 on top. The old rows survive and a new stock graph can be written.
      Flyway.configure()
          .dataSource(url, postgres.getUsername(), postgres.getPassword())
          .load()
          .migrate();
      try (Connection upgrade =
          DriverManager.getConnection(url, postgres.getUsername(), postgres.getPassword())) {
        List<String> versions = new ArrayList<>();
        try (Statement statement = upgrade.createStatement();
            ResultSet rows =
                statement.executeQuery(
                    "SELECT version, success FROM flyway_schema_history ORDER BY installed_rank")) {
          while (rows.next()) {
            assertThat(rows.getBoolean("success")).isTrue();
            versions.add(rows.getString("version"));
          }
        }
        assertThat(versions).containsExactly("1", "2", "3", "4");
        assertThat(count(upgrade, "inbox_event")).isEqualTo(1);
        Graph graph = seedForTenant(upgrade, tenant);
        assertThat(countFor(upgrade, "inventory", graph.tenant())).isEqualTo(1);
      }
    } finally {
      try (Connection admin = openAdmin();
          Statement statement = admin.createStatement()) {
        statement.execute("DROP DATABASE IF EXISTS " + database + " WITH (FORCE)");
      }
    }
  }

  @Test
  void everyV4TableIsTenantIsolatedForApp() throws SQLException {
    Graph a;
    Graph b;
    try (Connection admin = openAdmin()) {
      a = seed(admin);
      b = seed(admin);
    }

    try (Connection app = openApp()) {
      // Step 1: No context means no rows, on every table.
      for (String table : V4_TABLES) {
        assertThat(count(app, table)).as(table).isZero();
      }

      app.setAutoCommit(false);
      setTenant(app, a.tenant());

      // Step 2: Tenant A sees exactly its own rows and none of B's.
      for (String table : V4_TABLES) {
        assertThat(count(app, table)).as(table).isEqualTo((long) ROWS_PER_GRAPH.get(table));
        assertThat(countFor(app, table, b.tenant())).as(table).isZero();
      }

      // Step 3: UPDATE and DELETE aimed at B touch nothing. A's own rows are updatable.
      for (String table : V4_TABLES) {
        if (table.equals("inventory_ledger")) {
          continue;
        }
        assertThat(
                update(
                    app, "UPDATE " + table + " SET tenant_id = tenant_id WHERE tenant_id = ?", b))
            .as(table)
            .isZero();
        assertThat(update(app, "DELETE FROM " + table + " WHERE tenant_id = ?", b))
            .as(table)
            .isZero();
        assertThat(
                update(
                    app, "UPDATE " + table + " SET tenant_id = tenant_id WHERE tenant_id = ?", a))
            .as(table)
            .isEqualTo((int) ROWS_PER_GRAPH.get(table));
      }
      assertSqlState(
          app,
          "42501",
          "permission denied",
          () -> update(app, "UPDATE inventory_ledger SET actor = 'x' WHERE tenant_id = ?", b));
      assertSqlState(
          app,
          "42501",
          "permission denied",
          () -> update(app, "DELETE FROM inventory_ledger WHERE tenant_id = ?", b));

      // Step 4: A row carrying B's tenant_id fails WITH CHECK, on every table.
      Map<String, SqlAction> crossTenantInserts = new LinkedHashMap<>();
      crossTenantInserts.put(
          "channel_account",
          () -> insertChannelAccount(app, UUID.randomUUID(), b.tenant(), "x-" + UUID.randomUUID()));
      crossTenantInserts.put("product", () -> insertProduct(app, UUID.randomUUID(), b.tenant()));
      crossTenantInserts.put(
          "sku", () -> insertSku(app, UUID.randomUUID(), b.tenant(), b.product(), false));
      crossTenantInserts.put(
          "sku_bundle_component",
          () -> insertComponent(app, b.tenant(), b.bundle(), b.spareSku(), 1));
      crossTenantInserts.put(
          "channel_listing",
          () -> insertListing(app, UUID.randomUUID(), b.tenant(), b.channelAccount(), b.sku()));
      crossTenantInserts.put(
          "warehouse", () -> insertWarehouse(app, UUID.randomUUID(), b.tenant(), false));
      crossTenantInserts.put(
          "inventory",
          () -> insertInventory(app, UUID.randomUUID(), b.tenant(), b.spareSku(), b.warehouse()));
      crossTenantInserts.put(
          "inventory_ledger",
          () -> insertLedger(app, UUID.randomUUID(), b.tenant(), b.sku(), b.warehouse()));
      crossTenantInserts.put(
          "stock_reservation",
          () ->
              insertReservation(
                  app,
                  UUID.randomUUID(),
                  b.tenant(),
                  "x-" + UUID.randomUUID(),
                  b.sku(),
                  b.warehouse()));
      crossTenantInserts.put(
          "stock_document", () -> insertDocument(app, UUID.randomUUID(), b.tenant()));
      crossTenantInserts.put(
          "stock_document_line",
          () ->
              insertLine(app, UUID.randomUUID(), b.tenant(), b.document(), b.sku(), b.warehouse()));
      assertThat(crossTenantInserts.keySet()).containsExactlyInAnyOrderElementsOf(V4_TABLES);
      for (Map.Entry<String, SqlAction> insert : crossTenantInserts.entrySet()) {
        assertSqlState(app, "42501", "row-level security", insert.getValue(), insert.getKey());
      }

      // Step 5: A's own row pointing at B's parent fails the composite foreign key.
      Map<String, SqlAction> crossTenantParents = new LinkedHashMap<>();
      crossTenantParents.put(
          "sku.product", () -> insertSku(app, UUID.randomUUID(), a.tenant(), b.product(), false));
      crossTenantParents.put(
          "sku_bundle_component.component",
          () -> insertComponent(app, a.tenant(), a.bundle(), b.spareSku(), 1));
      crossTenantParents.put(
          "channel_listing.channel_account",
          () -> insertListing(app, UUID.randomUUID(), a.tenant(), b.channelAccount(), a.sku()));
      crossTenantParents.put(
          "channel_listing.sku",
          () -> insertListing(app, UUID.randomUUID(), a.tenant(), a.channelAccount(), b.sku()));
      crossTenantParents.put(
          "inventory.sku",
          () -> insertInventory(app, UUID.randomUUID(), a.tenant(), b.spareSku(), a.warehouse()));
      crossTenantParents.put(
          "inventory.warehouse",
          () -> insertInventory(app, UUID.randomUUID(), a.tenant(), a.spareSku(), b.warehouse()));
      crossTenantParents.put(
          "inventory_ledger.inventory",
          () -> insertLedger(app, UUID.randomUUID(), a.tenant(), b.sku(), b.warehouse()));
      crossTenantParents.put(
          "stock_reservation.sku",
          () ->
              insertReservation(app, UUID.randomUUID(), a.tenant(), "r1", b.sku(), a.warehouse()));
      crossTenantParents.put(
          "stock_reservation.warehouse",
          () ->
              insertReservation(app, UUID.randomUUID(), a.tenant(), "r2", a.sku(), b.warehouse()));
      crossTenantParents.put(
          "stock_document_line.document",
          () ->
              insertLine(app, UUID.randomUUID(), a.tenant(), b.document(), a.sku(), a.warehouse()));
      crossTenantParents.put(
          "stock_document_line.sku",
          () ->
              insertLine(app, UUID.randomUUID(), a.tenant(), a.document(), b.sku(), a.warehouse()));
      crossTenantParents.put(
          "stock_document_line.warehouse",
          () ->
              insertLine(app, UUID.randomUUID(), a.tenant(), a.document(), a.sku(), b.warehouse()));
      for (Map.Entry<String, SqlAction> insert : crossTenantParents.entrySet()) {
        assertSqlState(app, "23503", "foreign key", insert.getValue(), insert.getKey());
      }
      app.rollback();
    }

    // Step 6: B's rows are untouched.
    try (Connection admin = openAdmin()) {
      for (String table : V4_TABLES) {
        assertThat(countFor(admin, table, b.tenant()))
            .as(table)
            .isEqualTo((long) ROWS_PER_GRAPH.get(table));
      }
    }
  }

  @Test
  void inventoryQuantitiesAreChecked() throws SQLException {
    Graph a;
    try (Connection admin = openAdmin()) {
      a = seed(admin);
    }
    try (Connection app = openApp()) {
      app.setAutoCommit(false);
      setTenant(app, a.tenant());

      // Step 1: reserved above on_hand, and negative values, violate the CHECK.
      for (String set : List.of("reserved = on_hand + 1", "on_hand = -1", "reserved = -1")) {
        assertCheck(
            app,
            "inventory_quantity_check",
            () -> update(app, "UPDATE inventory SET " + set + " WHERE tenant_id = ?", a));
      }
      assertThat(update(app, "UPDATE inventory SET reserved = on_hand WHERE tenant_id = ?", a))
          .isEqualTo(1);

      // Step 2: One stock row per (sku, warehouse).
      assertSqlState(
          app,
          "23505",
          "inventory_tenant_sku_warehouse_key",
          () -> insertInventory(app, UUID.randomUUID(), a.tenant(), a.sku(), a.warehouse()));

      // Step 3: A ledger reason outside the 11 values is rejected.
      assertCheck(
          app,
          "inventory_ledger_reason_check",
          () -> insertLedger(app, UUID.randomUUID(), a.tenant(), a.sku(), a.warehouse(), "LOST"));

      // Step 4: One default warehouse per tenant.
      assertSqlState(
          app,
          "23505",
          "warehouse_one_default_per_tenant_idx",
          () -> insertWarehouse(app, UUID.randomUUID(), a.tenant(), true));
      insertWarehouse(app, UUID.randomUUID(), a.tenant(), false);
      app.rollback();
    }
  }

  @Test
  void bundlesCannotNestOrHoldStock() throws SQLException {
    Graph a;
    try (Connection admin = openAdmin()) {
      a = seed(admin);
    }
    try (Connection app = openApp()) {
      app.setAutoCommit(false);
      setTenant(app, a.tenant());
      UUID otherBundle = UUID.randomUUID();
      insertSku(app, otherBundle, a.tenant(), a.product(), true);

      // Step 1: A bundle cannot be a component, including of itself.
      assertBundleError(
          app,
          "NESTED_BUNDLE",
          "sku_bundle_nesting",
          () -> insertComponent(app, a.tenant(), a.bundle(), otherBundle, 1));
      assertBundleError(
          app,
          "NESTED_BUNDLE",
          "sku_bundle_nesting",
          () -> insertComponent(app, a.tenant(), a.bundle(), a.bundle(), 1));

      // Step 2: Components hang off bundle SKUs only, with qty > 0.
      assertBundleError(
          app,
          "BUNDLE_REQUIRED",
          "sku_bundle_parent",
          () -> insertComponent(app, a.tenant(), a.sku(), a.spareSku(), 1));
      assertCheck(
          app,
          "sku_bundle_component_qty_check",
          () -> insertComponent(app, a.tenant(), a.bundle(), a.spareSku(), 0));

      // Step 3: is_bundle cannot flip on a SKU that is a component or has components.
      assertBundleError(
          app,
          "NESTED_BUNDLE",
          "sku_bundle_nesting",
          () -> update(app, "UPDATE sku SET is_bundle = true WHERE id = ?", a.sku()));
      assertBundleError(
          app,
          "BUNDLE_REQUIRED",
          "sku_bundle_parent",
          () -> update(app, "UPDATE sku SET is_bundle = false WHERE id = ?", a.bundle()));

      // Step 4: Bundle SKUs never get inventory, reservations, or document lines.
      assertBundleError(
          app,
          "BUNDLE_NOT_STOCKABLE",
          "sku_bundle_stock",
          () -> insertInventory(app, UUID.randomUUID(), a.tenant(), a.bundle(), a.warehouse()));
      // Change: a reservation needs an inventory row (FK). Bundles never have one.
      assertSqlState(
          app,
          "23503",
          "stock_reservation_inventory_fkey",
          () ->
              insertReservation(
                  app, UUID.randomUUID(), a.tenant(), "order-x", a.bundle(), a.warehouse()));
      assertBundleError(
          app,
          "BUNDLE_NOT_STOCKABLE",
          "sku_bundle_stock",
          () ->
              insertLine(
                  app, UUID.randomUUID(), a.tenant(), a.document(), a.bundle(), a.warehouse()));
      assertBundleError(
          app,
          "BUNDLE_NOT_STOCKABLE",
          "sku_bundle_stock",
          () ->
              update(
                  app, "UPDATE inventory SET sku_id = ? WHERE id = ?", a.bundle(), a.inventory()));

      // Step 5: A SKU with stock rows cannot become a bundle.
      insertInventory(app, UUID.randomUUID(), a.tenant(), a.spareSku(), a.warehouse());
      assertBundleError(
          app,
          "BUNDLE_NOT_STOCKABLE",
          "sku_bundle_stock",
          () -> update(app, "UPDATE sku SET is_bundle = true WHERE id = ?", a.spareSku()));

      // Step 6: Unreferenced SKUs flip freely, and a valid component is accepted.
      UUID plain = UUID.randomUUID();
      insertSku(app, plain, a.tenant(), a.product(), false);
      assertThat(update(app, "UPDATE sku SET is_bundle = true WHERE id = ?", plain)).isEqualTo(1);
      assertThat(update(app, "UPDATE sku SET is_bundle = false WHERE id = ?", otherBundle))
          .isEqualTo(1);
      insertComponent(app, a.tenant(), a.bundle(), otherBundle, 3);
      app.rollback();
    }
  }

  @Test
  void bundleChecksLockTheSkuAgainstConcurrentFlips() throws SQLException {
    Graph a;
    UUID first = UUID.randomUUID();
    UUID second = UUID.randomUUID();
    try (Connection admin = openAdmin()) {
      a = seed(admin);
      insertSku(admin, first, a.tenant(), a.product(), false);
      insertSku(admin, second, a.tenant(), a.product(), false);
    }

    try (Connection left = openApp();
        Connection right = openApp()) {
      left.setAutoCommit(false);
      right.setAutoCommit(false);

      // Step 1: An uncommitted component insert blocks the flip of that SKU to bundle.
      setTenant(left, a.tenant());
      insertComponent(left, a.tenant(), a.bundle(), first, 1);
      setTenant(right, a.tenant());
      lockTimeout(right);
      assertSqlState(
          right,
          "55P03",
          "lock",
          () -> update(right, "UPDATE sku SET is_bundle = true WHERE id = ?", first));
      left.commit();
      right.rollback();

      // Step 2: After the insert commits, the flip sees it and is rejected.
      setTenant(right, a.tenant());
      assertBundleError(
          right,
          "NESTED_BUNDLE",
          "sku_bundle_nesting",
          () -> update(right, "UPDATE sku SET is_bundle = true WHERE id = ?", first));
      right.rollback();

      // Step 3: The other order. An uncommitted flip blocks a component insert, which then
      // sees the committed bundle and is rejected.
      setTenant(right, a.tenant());
      assertThat(update(right, "UPDATE sku SET is_bundle = true WHERE id = ?", second))
          .isEqualTo(1);
      setTenant(left, a.tenant());
      lockTimeout(left);
      assertSqlState(
          left, "55P03", "lock", () -> insertComponent(left, a.tenant(), a.bundle(), second, 1));
      right.commit();
      left.rollback();
      setTenant(left, a.tenant());
      assertBundleError(
          left,
          "NESTED_BUNDLE",
          "sku_bundle_nesting",
          () -> insertComponent(left, a.tenant(), a.bundle(), second, 1));
      left.rollback();
    }
  }

  @Test
  void activeReservationIsUniquePerOwnerAndSku() throws SQLException {
    Graph a;
    try (Connection admin = openAdmin()) {
      a = seed(admin);
    }
    try (Connection app = openApp()) {
      app.setAutoCommit(false);
      setTenant(app, a.tenant());
      UUID first = UUID.randomUUID();
      insertReservation(app, first, a.tenant(), "checkout-1", a.sku(), a.warehouse());

      // Step 1: A second ACTIVE row for the same owner and SKU is a unique violation.
      assertSqlState(
          app,
          "23505",
          "stock_reservation_active_owner_sku_key",
          () ->
              insertReservation(
                  app, UUID.randomUUID(), a.tenant(), "checkout-1", a.sku(), a.warehouse()));

      // Step 2: Another SKU for the same owner is fine. So is a new row once the first is RELEASED.
      // Change: the other SKU needs its own inventory row first.
      insertInventory(app, UUID.randomUUID(), a.tenant(), a.spareSku(), a.warehouse());
      insertReservation(
          app, UUID.randomUUID(), a.tenant(), "checkout-1", a.spareSku(), a.warehouse());
      assertThat(
              update(app, "UPDATE stock_reservation SET status = 'RELEASED' WHERE id = ?", first))
          .isEqualTo(1);
      insertReservation(app, UUID.randomUUID(), a.tenant(), "checkout-1", a.sku(), a.warehouse());

      // Step 3: qty must be positive, and the enum columns are checked.
      assertCheck(
          app,
          "stock_reservation_qty_check",
          () -> update(app, "UPDATE stock_reservation SET qty = 0 WHERE id = ?", first));
      assertCheck(
          app,
          "stock_reservation_status_check",
          () -> update(app, "UPDATE stock_reservation SET status = 'HELD' WHERE id = ?", first));
      assertCheck(
          app,
          "stock_reservation_owner_type_check",
          () ->
              update(app, "UPDATE stock_reservation SET owner_type = 'CART' WHERE id = ?", first));
      app.rollback();
    }
  }

  @Test
  void inventoryLedgerIsAppendOnly() throws SQLException {
    Graph a;
    try (Connection admin = openAdmin()) {
      a = seed(admin);

      // Step 1: The superuser is rejected by the trigger on UPDATE, DELETE, and TRUNCATE.
      admin.setAutoCommit(false);
      for (String sql :
          List.of(
              "UPDATE inventory_ledger SET actor = 'changed'",
              "DELETE FROM inventory_ledger",
              "TRUNCATE TABLE inventory_ledger")) {
        assertSqlState(admin, "P0001", "append-only", () -> execute(admin, sql), sql);
      }
      admin.rollback();
    }

    // Step 2: The table owner is rejected too.
    try (Connection owner = openMigrator()) {
      owner.setAutoCommit(false);
      setTenant(owner, a.tenant());
      assertThat(count(owner, "inventory_ledger")).isEqualTo(1);
      assertSqlState(
          owner,
          "P0001",
          "append-only",
          () -> update(owner, "UPDATE inventory_ledger SET actor = 'x' WHERE tenant_id = ?", a));
      assertSqlState(
          owner,
          "P0001",
          "append-only",
          () -> update(owner, "DELETE FROM inventory_ledger WHERE tenant_id = ?", a));
      owner.rollback();
    }

    // Step 3: oms_app can append, and has no UPDATE/DELETE privilege at all.
    try (Connection app = openApp()) {
      app.setAutoCommit(false);
      setTenant(app, a.tenant());
      insertLedger(app, UUID.randomUUID(), a.tenant(), a.sku(), a.warehouse());
      assertSqlState(
          app,
          "42501",
          "permission denied",
          () -> update(app, "DELETE FROM inventory_ledger WHERE tenant_id = ?", a));
      app.rollback();
    }
    try (Connection admin = openAdmin()) {
      assertThat(count(admin, "inventory_ledger")).isEqualTo(1);
    }
  }

  @Test
  void stockDocumentsAreImmutableAfterPost() throws SQLException {
    Graph a;
    try (Connection admin = openAdmin()) {
      a = seed(admin);
    }
    try (Connection app = openApp()) {
      app.setAutoCommit(false);
      setTenant(app, a.tenant());
      UUID doc = a.document();

      // Step 1: A DRAFT header and its lines are editable. DRAFT -> VOID is not allowed.
      assertThat(update(app, "UPDATE stock_document SET note = 'draft' WHERE id = ?", doc))
          .isEqualTo(1);
      assertThat(update(app, "UPDATE stock_document_line SET qty = 7 WHERE id = ?", a.line()))
          .isEqualTo(1);
      assertImmutable(
          app,
          () ->
              update(
                  app,
                  "UPDATE stock_document SET status = 'VOID', posted_at = now() WHERE id = ?",
                  doc));
      assertCheck(
          app,
          "stock_document_posted_at_check",
          () -> update(app, "UPDATE stock_document SET status = 'POSTED' WHERE id = ?", doc));

      // Step 2: DRAFT -> POSTED.
      assertThat(
              update(
                  app,
                  "UPDATE stock_document SET status = 'POSTED', posted_at = now() WHERE id = ?",
                  doc))
          .isEqualTo(1);

      // Step 3: A POSTED header and its lines are frozen. Only POSTED -> VOID is allowed.
      assertImmutable(
          app, () -> update(app, "UPDATE stock_document SET note = 'edit' WHERE id = ?", doc));
      assertImmutable(
          app,
          () ->
              update(
                  app,
                  "UPDATE stock_document SET status = 'DRAFT', posted_at = NULL WHERE id = ?",
                  doc));
      assertImmutable(
          app,
          () ->
              update(
                  app, "UPDATE stock_document SET status = 'VOID', note = 'x' WHERE id = ?", doc));
      assertImmutable(app, () -> update(app, "DELETE FROM stock_document WHERE id = ?", doc));
      assertImmutable(
          app, () -> update(app, "UPDATE stock_document_line SET qty = 8 WHERE id = ?", a.line()));
      assertImmutable(
          app, () -> update(app, "DELETE FROM stock_document_line WHERE id = ?", a.line()));
      assertImmutable(
          app,
          () -> insertLine(app, UUID.randomUUID(), a.tenant(), doc, a.spareSku(), a.warehouse()));
      assertImmutable(
          app,
          () ->
              update(
                  app,
                  "UPDATE stock_document_line SET document_id = ? WHERE id = ?",
                  doc,
                  moveTarget(app, a)));

      // Step 4: POSTED -> VOID, then VOID is frozen.
      assertThat(update(app, "UPDATE stock_document SET status = 'VOID' WHERE id = ?", doc))
          .isEqualTo(1);
      assertImmutable(
          app, () -> update(app, "UPDATE stock_document SET status = 'POSTED' WHERE id = ?", doc));
      assertImmutable(
          app, () -> update(app, "UPDATE stock_document SET note = 'void' WHERE id = ?", doc));
      assertImmutable(app, () -> update(app, "DELETE FROM stock_document WHERE id = ?", doc));

      // Step 5: A DRAFT document can be deleted once its lines are gone.
      UUID draft = UUID.randomUUID();
      UUID draftLine = UUID.randomUUID();
      insertDocument(app, draft, a.tenant());
      insertLine(app, draftLine, a.tenant(), draft, a.sku(), a.warehouse());
      assertSqlState(
          app,
          "23503",
          "foreign key",
          () -> update(app, "DELETE FROM stock_document WHERE id = ?", draft));
      assertThat(update(app, "DELETE FROM stock_document_line WHERE id = ?", draftLine))
          .isEqualTo(1);
      assertThat(update(app, "DELETE FROM stock_document WHERE id = ?", draft)).isEqualTo(1);
      app.rollback();
    }
  }

  @Test
  void reservationRequiresInventoryAndDoesNotLockSku() throws SQLException {
    Graph a;
    UUID other = UUID.randomUUID();
    try (Connection admin = openAdmin()) {
      a = seed(admin);
      insertSku(admin, other, a.tenant(), a.product(), false);
      insertInventory(admin, UUID.randomUUID(), a.tenant(), other, a.warehouse());
    }

    try (Connection left = openApp();
        Connection right = openApp()) {
      left.setAutoCommit(false);
      right.setAutoCommit(false);

      // Step 1: A reservation without an inventory row fails the foreign key.
      setTenant(left, a.tenant());
      assertSqlState(
          left,
          "23503",
          "stock_reservation_inventory_fkey",
          () ->
              insertReservation(
                  left, UUID.randomUUID(), a.tenant(), "order-y", a.spareSku(), a.warehouse()));

      // Step 2: Open reservations on two SKUs (low id first) do not hold sku row locks.
      // A catalog rename in the opposite order runs without waiting, so the two cannot
      // deadlock. lock_timeout turns any wait into 55P03.
      List<UUID> skus = new ArrayList<>(List.of(a.sku(), other));
      skus.sort(null);
      for (UUID sku : skus) {
        insertReservation(left, UUID.randomUUID(), a.tenant(), "order-z", sku, a.warehouse());
      }
      setTenant(right, a.tenant());
      lockTimeout(right);
      for (UUID sku : List.of(skus.get(1), skus.get(0))) {
        assertThat(update(right, "UPDATE sku SET name = 'Renamed' WHERE id = ?", sku)).isEqualTo(1);
      }
      right.commit();
      left.commit();
    }

    // Step 3: A SKU with a reservation (and so an inventory row) still cannot become a bundle.
    try (Connection app = openApp()) {
      app.setAutoCommit(false);
      setTenant(app, a.tenant());
      assertBundleError(
          app,
          "BUNDLE_NOT_STOCKABLE",
          "sku_bundle_stock",
          () -> update(app, "UPDATE sku SET is_bundle = true WHERE id = ?", other));
      app.rollback();
    }
  }

  @Test
  void bundleFlipAndPostRequireReadCommitted() throws SQLException {
    Graph a;
    UUID plain = UUID.randomUUID();
    try (Connection admin = openAdmin()) {
      a = seed(admin);
      insertSku(admin, plain, a.tenant(), a.product(), false);
    }

    try (Connection app = openApp()) {
      app.setAutoCommit(false);
      for (int level :
          List.of(Connection.TRANSACTION_REPEATABLE_READ, Connection.TRANSACTION_SERIALIZABLE)) {
        // Step 1: At a snapshot level the flip is refused, even for an unreferenced SKU.
        app.setTransactionIsolation(level);
        setTenant(app, a.tenant());
        PSQLException flip =
            assertSqlState(
                app,
                "23514",
                "READ_COMMITTED_REQUIRED",
                () -> update(app, "UPDATE sku SET is_bundle = true WHERE id = ?", plain),
                "flip");
        assertThat(flip.getServerErrorMessage().getConstraint()).isEqualTo("sku_bundle_isolation");

        // Step 2: Posting is refused too. Other edits are unaffected by the level.
        PSQLException post =
            assertSqlState(
                app,
                "23514",
                "READ_COMMITTED_REQUIRED",
                () ->
                    update(
                        app,
                        "UPDATE stock_document SET status = 'POSTED', posted_at = now() "
                            + "WHERE id = ?",
                        a.document()),
                "post");
        assertThat(post.getServerErrorMessage().getConstraint())
            .isEqualTo("stock_document_isolation");
        assertThat(update(app, "UPDATE sku SET name = 'Renamed' WHERE id = ?", plain)).isEqualTo(1);
        assertThat(update(app, "UPDATE stock_document SET note = 'n' WHERE id = ?", a.document()))
            .isEqualTo(1);
        app.rollback();
      }

      // Step 3: Back at READ COMMITTED both work.
      app.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
      setTenant(app, a.tenant());
      assertThat(update(app, "UPDATE sku SET is_bundle = true WHERE id = ?", plain)).isEqualTo(1);
      assertThat(
              update(
                  app,
                  "UPDATE stock_document SET status = 'POSTED', posted_at = now() WHERE id = ?",
                  a.document()))
          .isEqualTo(1);
      app.rollback();
    }
  }

  @Test
  void documentPostWaitsForConcurrentLineWrite() throws SQLException {
    Graph a;
    try (Connection admin = openAdmin()) {
      a = seed(admin);
    }
    try (Connection left = openApp();
        Connection right = openApp()) {
      left.setAutoCommit(false);
      right.setAutoCommit(false);

      // Step 1: An open line write holds the document FOR SHARE, so a post cannot slip past it.
      setTenant(left, a.tenant());
      assertThat(update(left, "UPDATE stock_document_line SET qty = 9 WHERE id = ?", a.line()))
          .isEqualTo(1);
      setTenant(right, a.tenant());
      lockTimeout(right);
      assertSqlState(
          right,
          "55P03",
          "lock",
          () ->
              update(
                  right,
                  "UPDATE stock_document SET status = 'POSTED', posted_at = now() WHERE id = ?",
                  a.document()));
      right.rollback();
      left.commit();
    }
  }

  // A second DRAFT document with one line, used to try moving a line into a POSTED document.
  private static UUID moveTarget(Connection app, Graph a) throws SQLException {
    UUID draft = UUID.randomUUID();
    UUID line = UUID.randomUUID();
    insertDocument(app, draft, a.tenant());
    insertLine(app, line, a.tenant(), draft, a.sku(), a.warehouse());
    return line;
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

  private static void setTenant(Connection connection, UUID tenantId) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement("SELECT set_config('app.tenant_id', ?, true)")) {
      statement.setString(1, tenantId.toString());
      statement.execute();
    }
  }

  private static void lockTimeout(Connection connection) throws SQLException {
    execute(connection, "SET LOCAL lock_timeout = '500ms'");
  }

  private static void execute(Connection connection, String sql) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute(sql);
    }
  }

  private static int update(Connection connection, String sql, Object... params)
      throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      for (int i = 0; i < params.length; i++) {
        Object param = params[i] instanceof Graph graph ? graph.tenant() : params[i];
        statement.setObject(i + 1, param);
      }
      return statement.executeUpdate();
    }
  }

  private static long count(Connection connection, String table) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet rows = statement.executeQuery("SELECT count(*) FROM " + table)) {
      assertThat(rows.next()).isTrue();
      return rows.getLong(1);
    }
  }

  private static long countFor(Connection connection, String table, UUID tenantId)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement("SELECT count(*) FROM " + table + " WHERE tenant_id = ?")) {
      statement.setObject(1, tenantId);
      return single(statement);
    }
  }

  private static long single(PreparedStatement statement) throws SQLException {
    try (ResultSet rows = statement.executeQuery()) {
      assertThat(rows.next()).isTrue();
      return rows.getLong(1);
    }
  }

  private static boolean tablePrivilege(
      Connection admin, String role, String table, String privilege) throws SQLException {
    try (PreparedStatement statement =
        admin.prepareStatement("SELECT has_table_privilege(?, ?, ?)")) {
      statement.setString(1, role);
      statement.setString(2, "public." + table);
      statement.setString(3, privilege);
      try (ResultSet rows = statement.executeQuery()) {
        assertThat(rows.next()).isTrue();
        return rows.getBoolean(1);
      }
    }
  }

  private static void assertSqlState(
      Connection connection, String sqlState, String messagePart, SqlAction action)
      throws SQLException {
    assertSqlState(connection, sqlState, messagePart, action, messagePart);
  }

  private static PSQLException assertSqlState(
      Connection connection, String sqlState, String messagePart, SqlAction action, String label)
      throws SQLException {
    Savepoint savepoint = connection.getAutoCommit() ? null : connection.setSavepoint();
    try {
      action.run();
      fail("expected SQLSTATE " + sqlState + " for " + label);
      return null;
    } catch (PSQLException exception) {
      assertThat(exception.getSQLState())
          .as(label + ": " + exception.getMessage())
          .isEqualTo(sqlState);
      assertThat(exception.getMessage()).as(label).containsIgnoringCase(messagePart);
      return exception;
    } finally {
      if (savepoint != null) {
        connection.rollback(savepoint);
      }
    }
  }

  private static void assertCheck(Connection connection, String constraint, SqlAction action)
      throws SQLException {
    PSQLException exception = assertSqlState(connection, "23514", constraint, action, constraint);
    assertThat(exception.getServerErrorMessage().getConstraint()).isEqualTo(constraint);
  }

  private static void assertBundleError(
      Connection connection, String prefix, String constraint, SqlAction action)
      throws SQLException {
    PSQLException exception = assertSqlState(connection, "23514", prefix + ":", action, prefix);
    assertThat(exception.getServerErrorMessage().getMessage()).startsWith(prefix + ":");
    assertThat(exception.getServerErrorMessage().getConstraint()).isEqualTo(constraint);
  }

  private static void assertImmutable(Connection connection, SqlAction action) throws SQLException {
    assertBundleError(connection, "STOCK_DOCUMENT_IMMUTABLE", "stock_document_immutable", action);
  }

  private static Graph seed(Connection connection) throws SQLException {
    UUID tenant = UUID.randomUUID();
    insertTenant(connection, tenant);
    return seedForTenant(connection, tenant);
  }

  // One row per V4 table (three SKUs: two plain, one bundle of the first). See ROWS_PER_GRAPH.
  private static Graph seedForTenant(Connection connection, UUID tenant) throws SQLException {
    Graph graph =
        new Graph(
            tenant,
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID());
    insertChannelAccount(connection, graph.channelAccount(), tenant, "shop-" + tenant);
    insertProduct(connection, graph.product(), tenant);
    insertSku(connection, graph.sku(), tenant, graph.product(), false);
    insertSku(connection, graph.spareSku(), tenant, graph.product(), false);
    insertSku(connection, graph.bundle(), tenant, graph.product(), true);
    insertComponent(connection, tenant, graph.bundle(), graph.sku(), 2);
    insertListing(connection, UUID.randomUUID(), tenant, graph.channelAccount(), graph.sku());
    insertWarehouse(connection, graph.warehouse(), tenant, true);
    insertInventory(connection, graph.inventory(), tenant, graph.sku(), graph.warehouse());
    insertLedger(connection, UUID.randomUUID(), tenant, graph.sku(), graph.warehouse());
    insertReservation(
        connection, UUID.randomUUID(), tenant, "order-" + tenant, graph.sku(), graph.warehouse());
    insertDocument(connection, graph.document(), tenant);
    insertLine(connection, graph.line(), tenant, graph.document(), graph.sku(), graph.warehouse());
    return graph;
  }

  private static void insertTenant(Connection connection, UUID id) throws SQLException {
    insert(
        connection,
        "INSERT INTO tenant (id, name, tsf_shop_id, membership_tier, entitlement_status, ent_ver) "
            + "VALUES (?, 'Shop', ?, 'PRO', 'ACTIVE', 1)",
        id,
        "shop-" + id);
  }

  private static void insertChannelAccount(
      Connection connection, UUID id, UUID tenantId, String externalShopId) throws SQLException {
    insert(
        connection,
        "INSERT INTO channel_account (id, tenant_id, channel, external_shop_id, status, "
            + "credentials_ref) VALUES (?, ?, 'TSF', ?, 'CONNECTED', 'secret/tsf')",
        id,
        tenantId,
        externalShopId);
  }

  private static void insertProduct(Connection connection, UUID id, UUID tenantId)
      throws SQLException {
    insert(
        connection,
        "INSERT INTO product (id, tenant_id, name, status) VALUES (?, ?, 'Product', 'ACTIVE')",
        id,
        tenantId);
  }

  private static void insertSku(
      Connection connection, UUID id, UUID tenantId, UUID productId, boolean bundle)
      throws SQLException {
    insert(
        connection,
        "INSERT INTO sku (id, tenant_id, product_id, sku_code, name, is_bundle) "
            + "VALUES (?, ?, ?, ?, 'SKU', ?)",
        id,
        tenantId,
        productId,
        "SKU-" + id,
        bundle);
  }

  private static void insertComponent(
      Connection connection, UUID tenantId, UUID bundleId, UUID componentId, int qty)
      throws SQLException {
    insert(
        connection,
        "INSERT INTO sku_bundle_component (tenant_id, bundle_sku_id, component_sku_id, qty) "
            + "VALUES (?, ?, ?, ?)",
        tenantId,
        bundleId,
        componentId,
        qty);
  }

  private static void insertListing(
      Connection connection, UUID id, UUID tenantId, UUID channelAccountId, UUID skuId)
      throws SQLException {
    insert(
        connection,
        "INSERT INTO channel_listing (id, tenant_id, channel_account_id, sku_id, external_sku_id) "
            + "VALUES (?, ?, ?, ?, ?)",
        id,
        tenantId,
        channelAccountId,
        skuId,
        "ext-" + id);
  }

  private static void insertWarehouse(
      Connection connection, UUID id, UUID tenantId, boolean isDefault) throws SQLException {
    insert(
        connection,
        "INSERT INTO warehouse (id, tenant_id, code, name, is_default) "
            + "VALUES (?, ?, ?, 'Main', ?)",
        id,
        tenantId,
        "WH-" + id,
        isDefault);
  }

  private static void insertInventory(
      Connection connection, UUID id, UUID tenantId, UUID skuId, UUID warehouseId)
      throws SQLException {
    insert(
        connection,
        "INSERT INTO inventory (id, tenant_id, sku_id, warehouse_id, on_hand, reserved) "
            + "VALUES (?, ?, ?, ?, 10, 1)",
        id,
        tenantId,
        skuId,
        warehouseId);
  }

  private static void insertLedger(
      Connection connection, UUID id, UUID tenantId, UUID skuId, UUID warehouseId)
      throws SQLException {
    insertLedger(connection, id, tenantId, skuId, warehouseId, "OPENING_BALANCE");
  }

  private static void insertLedger(
      Connection connection, UUID id, UUID tenantId, UUID skuId, UUID warehouseId, String reason)
      throws SQLException {
    insert(
        connection,
        "INSERT INTO inventory_ledger (id, tenant_id, sku_id, warehouse_id, delta_on_hand, "
            + "delta_reserved, reason, actor) VALUES (?, ?, ?, ?, 10, 0, ?, 'test')",
        id,
        tenantId,
        skuId,
        warehouseId,
        reason);
  }

  private static void insertReservation(
      Connection connection, UUID id, UUID tenantId, String ownerRef, UUID skuId, UUID warehouseId)
      throws SQLException {
    insert(
        connection,
        "INSERT INTO stock_reservation (id, tenant_id, owner_type, owner_ref, sku_id, "
            + "warehouse_id, qty, status, expires_at, reservation_group_id) "
            + "VALUES (?, ?, 'CHECKOUT', ?, ?, ?, 1, 'ACTIVE', now() + interval '15 minutes', ?)",
        id,
        tenantId,
        ownerRef,
        skuId,
        warehouseId,
        UUID.randomUUID());
  }

  private static void insertDocument(Connection connection, UUID id, UUID tenantId)
      throws SQLException {
    insert(
        connection,
        "INSERT INTO stock_document (id, tenant_id, type, status) "
            + "VALUES (?, ?, 'RECEIVE', 'DRAFT')",
        id,
        tenantId);
  }

  private static void insertLine(
      Connection connection, UUID id, UUID tenantId, UUID documentId, UUID skuId, UUID warehouseId)
      throws SQLException {
    insert(
        connection,
        "INSERT INTO stock_document_line (id, tenant_id, document_id, sku_id, warehouse_id, qty) "
            + "VALUES (?, ?, ?, ?, ?, 5)",
        id,
        tenantId,
        documentId,
        skuId,
        warehouseId);
  }

  private static void insert(Connection connection, String sql, Object... params)
      throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      for (int i = 0; i < params.length; i++) {
        statement.setObject(i + 1, params[i]);
      }
      assertThat(statement.executeUpdate()).isEqualTo(1);
    }
  }

  @FunctionalInterface
  private interface SqlAction {
    void run() throws SQLException;
  }

  private record Graph(
      UUID tenant,
      UUID channelAccount,
      UUID product,
      UUID sku,
      UUID spareSku,
      UUID bundle,
      UUID warehouse,
      UUID inventory,
      UUID document,
      UUID line) {}

  private record TableSecurity(
      boolean rowSecurity, boolean forceRowSecurity, String owner, int policies, String policy) {}
}
