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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.postgresql.util.PSQLException;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * V7 order-side schema. Constraints, triggers, and RLS are exercised as {@code oms_app}
 * (NOBYPASSRLS, not an owner) unless a case is about the owner or the superuser. Every test works
 * in fresh tenants. The generic FORCE RLS check in {@link FlywayV4CatalogStockTest} also covers
 * these tables, because it scans every table with a tenant_id column.
 */
@ActiveProfiles("test")
@SpringBootTest
@Testcontainers
class FlywayV7OrdersTest {

  private static final String APP_PASSWORD = "oms-app-test-only";
  private static final String MIGRATOR_PASSWORD = "oms-migrator-test-only";

  static final Set<String> V7_TABLES =
      Set.of(
          "sales_order",
          "order_recipient",
          "order_line",
          "order_status_history",
          "shipment",
          "return_request",
          "return_line",
          "refund",
          "payment_status_snapshot",
          "sync_cursor",
          "shadow_diff",
          "reconciliation_issue");

  private static final Set<String> APPEND_ONLY =
      Set.of("order_status_history", "payment_status_snapshot");

  /** Rows one {@link #seed} call creates per table. The second order has no children. */
  private static final Map<String, Integer> ROWS_PER_GRAPH =
      Map.ofEntries(
          Map.entry("sales_order", 2),
          Map.entry("order_recipient", 1),
          Map.entry("order_line", 1),
          Map.entry("order_status_history", 1),
          Map.entry("shipment", 1),
          Map.entry("return_request", 1),
          Map.entry("return_line", 1),
          Map.entry("refund", 1),
          Map.entry("payment_status_snapshot", 1),
          Map.entry("sync_cursor", 1),
          Map.entry("shadow_diff", 1),
          Map.entry("reconciliation_issue", 1));

  private static final List<String> TRIGGER_FUNCTIONS =
      List.of(
          "order_append_only_reject_mutation",
          "return_line_qty_check",
          "order_line_return_qty_check",
          "return_request_rejected_guard");

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
  void enableTestLogins() throws SQLException {
    // Step 1: Let the test connect as the two non-superuser roles. Rows are per-tenant, no reset.
    try (Connection admin = openAdmin();
        Statement statement = admin.createStatement()) {
      statement.execute("ALTER ROLE oms_app LOGIN PASSWORD '" + APP_PASSWORD + "'");
      statement.execute("ALTER ROLE oms_migrator LOGIN PASSWORD '" + MIGRATOR_PASSWORD + "'");
    }
  }

  @Test
  void freshMigrateAppliesV7() throws SQLException {
    try (Connection admin = openAdmin()) {
      // Step 1: The full chain ran on the empty container, V5 skipped.
      assertThat(versions(admin))
          .containsExactly("1", "2", "3", "4", "6", "7", "8", "9", "10", "11", "12", "13", "14");
      // Step 2: Every V7 table exists, with the indexes 03-data-model.md requires.
      Set<String> tables = new HashSet<>();
      try (Statement statement = admin.createStatement();
          ResultSet rows =
              statement.executeQuery(
                  "SELECT tablename FROM pg_tables WHERE schemaname = 'public'")) {
        while (rows.next()) {
          tables.add(rows.getString(1));
        }
      }
      assertThat(tables).containsAll(V7_TABLES);
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
              "sales_order_channel_account_external_order_key",
              "sales_order_tenant_fulfillment_ordered_idx",
              "sales_order_tenant_ordered_id_desc_idx",
              "sales_order_tenant_hold_idx",
              "sales_order_tenant_ship_by_idx",
              "order_recipient_tenant_phone_hash_idx",
              "order_recipient_pii_status_redact_after_idx",
              "reconciliation_issue_open_key");
    }
  }

  @Test
  void migrationAppliesOnTopOfV6WithExistingData() throws SQLException {
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
      // Step 1: Stop at V6 and leave V4/V6 data behind: shop, channel, SKU, stock, a hold.
      Flyway.configure()
          .dataSource(url, postgres.getUsername(), postgres.getPassword())
          .target("6")
          .load()
          .migrate();
      UUID tenant = UUID.randomUUID();
      UUID channelAccount = UUID.randomUUID();
      UUID product = UUID.randomUUID();
      UUID sku = UUID.randomUUID();
      UUID warehouse = UUID.randomUUID();
      try (Connection upgrade =
          DriverManager.getConnection(url, postgres.getUsername(), postgres.getPassword())) {
        assertThat(versions(upgrade)).containsExactly("1", "2", "3", "4", "6");
        insertTenant(upgrade, tenant);
        insertChannelAccount(upgrade, channelAccount, tenant);
        insertProductSku(upgrade, tenant, product, sku);
        insertWarehouse(upgrade, warehouse, tenant);
        exec(
            upgrade,
            "INSERT INTO inventory (id, tenant_id, sku_id, warehouse_id, on_hand, reserved) "
                + "VALUES (?, ?, ?, ?, 10, 1)",
            UUID.randomUUID(),
            tenant,
            sku,
            warehouse);
        exec(
            upgrade,
            "INSERT INTO stock_reservation (id, tenant_id, owner_type, owner_ref, sku_id, "
                + "warehouse_id, qty, status, expires_at, reservation_group_id) "
                + "VALUES (?, ?, 'CHECKOUT', 'chk-1', ?, ?, 1, 'ACTIVE', now(), ?)",
            UUID.randomUUID(),
            tenant,
            sku,
            warehouse,
            UUID.randomUUID());
      }

      // Step 2: Apply V7. The old rows survive and an order graph can be written on them.
      Flyway.configure()
          .dataSource(url, postgres.getUsername(), postgres.getPassword())
          .load()
          .migrate();
      try (Connection upgrade =
          DriverManager.getConnection(url, postgres.getUsername(), postgres.getPassword())) {
        assertThat(versions(upgrade))
            .containsExactly("1", "2", "3", "4", "6", "7", "8", "9", "10", "11", "12", "13", "14");
        for (String table :
            List.of("tenant", "product", "sku", "warehouse", "inventory", "stock_reservation")) {
          assertThat(count(upgrade, table)).as(table).isEqualTo(1);
        }
        // V10 backfills a TSF channel_account for tsf_shop_id; the V6 seed row uses another id.
        assertThat(count(upgrade, "channel_account")).as("channel_account").isEqualTo(2);
        UUID order = UUID.randomUUID();
        insertOrder(upgrade, order, tenant, channelAccount, "EXT-1");
        insertLine(upgrade, UUID.randomUUID(), tenant, order, sku, 2);
        assertThat(count(upgrade, "sales_order")).isEqualTo(1);
        assertThat(count(upgrade, "order_line")).isEqualTo(1);
      }
    } finally {
      try (Connection admin = openAdmin();
          Statement statement = admin.createStatement()) {
        statement.execute("DROP DATABASE IF EXISTS " + database + " WITH (FORCE)");
      }
    }
  }

  @Test
  void grantsAndTriggerFunctionsAreLocked() throws SQLException {
    try (Connection admin = openAdmin()) {
      // Step 1: oms_app and oms_maint have DML on every V7 table, SELECT/INSERT only on the
      // append-only ones, and never TRUNCATE.
      for (String table : V7_TABLES) {
        boolean appendOnly = APPEND_ONLY.contains(table);
        for (String role : List.of("oms_app", "oms_maint")) {
          assertThat(tablePrivilege(admin, role, table, "SELECT")).as(role + " " + table).isTrue();
          assertThat(tablePrivilege(admin, role, table, "INSERT")).as(role + " " + table).isTrue();
          assertThat(tablePrivilege(admin, role, table, "UPDATE"))
              .as(role + " " + table)
              .isEqualTo(!appendOnly);
          assertThat(tablePrivilege(admin, role, table, "DELETE"))
              .as(role + " " + table)
              .isEqualTo(!appendOnly);
          assertThat(tablePrivilege(admin, role, table, "TRUNCATE"))
              .as(role + " " + table)
              .isFalse();
        }
      }

      // Step 2: Nothing on the V7 tables is granted to PUBLIC.
      try (PreparedStatement statement =
          admin.prepareStatement(
              "SELECT count(*) FROM pg_class c "
                  + "JOIN pg_namespace n ON n.oid = c.relnamespace, "
                  + "LATERAL aclexplode(c.relacl) AS a "
                  + "WHERE n.nspname = 'public' AND c.relname = ANY (?) AND a.grantee = 0")) {
        statement.setArray(1, admin.createArrayOf("text", V7_TABLES.toArray()));
        assertThat(single(statement)).isZero();
      }

      // Step 3: Trigger functions: owner oms_migrator, pinned search_path, not SECURITY
      // DEFINER, no PUBLIC execute. V7 adds no cross-tenant (definer) function at all.
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

      // Step 4: Only the recipient row cascades with its order. Every other V7 FK restricts.
      try (Statement statement = admin.createStatement();
          ResultSet rows =
              statement.executeQuery(
                  "SELECT c.conname, c.confdeltype FROM pg_constraint c "
                      + "JOIN pg_class t ON t.oid = c.conrelid "
                      + "WHERE c.contype = 'f' AND t.relname IN ("
                      + String.join(", ", V7_TABLES.stream().map(t -> "'" + t + "'").toList())
                      + ")")) {
        while (rows.next()) {
          String name = rows.getString("conname");
          String expected =
              name.equals("order_recipient_order_fkey")
                  ? "c"
                  : name.endsWith("_tenant_id_fkey") ? "a" : "r";
          assertThat(rows.getString("confdeltype")).as(name).isEqualTo(expected);
        }
      }
    }
  }

  @Test
  void everyV7TableIsTenantIsolatedForApp() throws SQLException {
    Graph a;
    Graph b;
    try (Connection admin = openAdmin()) {
      a = seed(admin);
      b = seed(admin);
    }

    try (Connection app = openApp()) {
      // Step 1: No context means no rows, on every table.
      for (String table : V7_TABLES) {
        assertThat(count(app, table)).as(table).isZero();
      }

      app.setAutoCommit(false);
      setTenant(app, a.tenant());

      // Step 2: Tenant A sees exactly its own rows and none of B's.
      for (String table : V7_TABLES) {
        assertThat(count(app, table)).as(table).isEqualTo((long) ROWS_PER_GRAPH.get(table));
        assertThat(countFor(app, table, b.tenant())).as(table).isZero();
      }

      // Step 3: UPDATE and DELETE aimed at B touch nothing. A's own rows are updatable. The
      // append-only tables have no UPDATE/DELETE grant at all.
      for (String table : V7_TABLES) {
        if (APPEND_ONLY.contains(table)) {
          for (String sql :
              List.of(
                  "UPDATE " + table + " SET tenant_id = tenant_id WHERE tenant_id = ?",
                  "DELETE FROM " + table + " WHERE tenant_id = ?")) {
            assertSqlState(app, "42501", "permission denied", () -> update(app, sql, b), table);
          }
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

      // Step 4: A row carrying B's tenant_id fails WITH CHECK, on every table.
      Map<String, SqlAction> crossTenantInserts = new LinkedHashMap<>();
      crossTenantInserts.put(
          "sales_order",
          () -> insertOrder(app, UUID.randomUUID(), b.tenant(), b.channelAccount(), "X-1"));
      crossTenantInserts.put(
          "order_recipient", () -> insertRecipient(app, b.tenant(), b.bareOrder()));
      crossTenantInserts.put(
          "order_line",
          () -> insertLine(app, UUID.randomUUID(), b.tenant(), b.order(), b.sku(), 1));
      crossTenantInserts.put(
          "order_status_history", () -> insertHistory(app, b.tenant(), b.order(), "ORDER"));
      crossTenantInserts.put(
          "shipment",
          () -> insertShipment(app, UUID.randomUUID(), b.tenant(), b.bareOrder(), b.warehouse()));
      crossTenantInserts.put(
          "return_request",
          () -> insertReturn(app, UUID.randomUUID(), b.tenant(), b.order(), "REQUESTED"));
      crossTenantInserts.put(
          "return_line",
          () ->
              insertReturnLine(
                  app, UUID.randomUUID(), b.tenant(), b.order(), b.returnRequest(), b.line(), 1));
      crossTenantInserts.put(
          "refund",
          () -> insertRefund(app, b.tenant(), b.order(), null, "evt-x-" + UUID.randomUUID()));
      crossTenantInserts.put(
          "payment_status_snapshot",
          () -> insertSnapshot(app, b.tenant(), b.order(), "XENDIT", "evt-x-" + UUID.randomUUID()));
      crossTenantInserts.put(
          "sync_cursor", () -> insertCursor(app, b.tenant(), b.channelAccount(), "LISTINGS"));
      crossTenantInserts.put(
          "shadow_diff", () -> insertShadowDiff(app, b.tenant(), b.channelAccount(), "STOCK"));
      crossTenantInserts.put(
          "reconciliation_issue", () -> insertIssue(app, b.tenant(), "rule-x", b.order(), "OPEN"));
      assertThat(crossTenantInserts.keySet()).containsExactlyInAnyOrderElementsOf(V7_TABLES);
      for (Map.Entry<String, SqlAction> insert : crossTenantInserts.entrySet()) {
        assertSqlState(app, "42501", "row-level security", insert.getValue(), insert.getKey());
      }

      // Step 5: A's own row pointing at B's parent fails the composite foreign key.
      Map<String, SqlAction> crossTenantParents = new LinkedHashMap<>();
      crossTenantParents.put(
          "sales_order.channel_account",
          () -> insertOrder(app, UUID.randomUUID(), a.tenant(), b.channelAccount(), "X-2"));
      crossTenantParents.put(
          "order_recipient.order", () -> insertRecipient(app, a.tenant(), b.bareOrder()));
      crossTenantParents.put(
          "order_line.order",
          () -> insertLine(app, UUID.randomUUID(), a.tenant(), b.order(), a.sku(), 1));
      crossTenantParents.put(
          "order_line.sku",
          () -> insertLine(app, UUID.randomUUID(), a.tenant(), a.order(), b.sku(), 1));
      crossTenantParents.put(
          "order_status_history.order", () -> insertHistory(app, a.tenant(), b.order(), "ORDER"));
      crossTenantParents.put(
          "shipment.order",
          () -> insertShipment(app, UUID.randomUUID(), a.tenant(), b.bareOrder(), a.warehouse()));
      crossTenantParents.put(
          "shipment.warehouse",
          () -> insertShipment(app, UUID.randomUUID(), a.tenant(), a.bareOrder(), b.warehouse()));
      crossTenantParents.put(
          "return_request.order",
          () -> insertReturn(app, UUID.randomUUID(), a.tenant(), b.order(), "REQUESTED"));
      crossTenantParents.put(
          "return_line.return",
          () ->
              insertReturnLine(
                  app, UUID.randomUUID(), a.tenant(), a.order(), b.returnRequest(), a.line(), 1));
      crossTenantParents.put(
          "return_line.order_line",
          () ->
              insertReturnLine(
                  app, UUID.randomUUID(), a.tenant(), a.order(), a.returnRequest(), b.line(), 1));
      crossTenantParents.put(
          "refund.order", () -> insertRefund(app, a.tenant(), b.order(), null, "evt-y-1"));
      crossTenantParents.put(
          "refund.return",
          () -> insertRefund(app, a.tenant(), a.order(), b.returnRequest(), "evt-y-2"));
      crossTenantParents.put(
          "payment_status_snapshot.order",
          () -> insertSnapshot(app, a.tenant(), b.order(), "OPN", "evt-y-3"));
      crossTenantParents.put(
          "sync_cursor.channel_account",
          () -> insertCursor(app, a.tenant(), b.channelAccount(), "LISTINGS"));
      crossTenantParents.put(
          "shadow_diff.channel_account",
          () -> insertShadowDiff(app, a.tenant(), b.channelAccount(), "ORDER"));
      crossTenantParents.put(
          "reconciliation_issue.order",
          () -> insertIssue(app, a.tenant(), "rule-y", b.order(), "OPEN"));
      for (Map.Entry<String, SqlAction> insert : crossTenantParents.entrySet()) {
        assertSqlState(app, "23503", "foreign key", insert.getValue(), insert.getKey());
      }
      app.rollback();
    }

    // Step 6: B's rows are untouched.
    try (Connection admin = openAdmin()) {
      for (String table : V7_TABLES) {
        assertThat(countFor(admin, table, b.tenant()))
            .as(table)
            .isEqualTo((long) ROWS_PER_GRAPH.get(table));
      }
    }
  }

  @Test
  void externalOrderIdIsUniquePerChannelAccount() throws SQLException {
    Graph a;
    UUID secondAccount = UUID.randomUUID();
    try (Connection admin = openAdmin()) {
      a = seed(admin);
      insertChannelAccount(admin, secondAccount, a.tenant());
    }
    try (Connection app = openApp()) {
      app.setAutoCommit(false);
      setTenant(app, a.tenant());
      // Step 1: The same external id on the same channel account is a unique violation.
      assertSqlState(
          app,
          "23505",
          "sales_order_channel_account_external_order_key",
          () ->
              insertOrder(app, UUID.randomUUID(), a.tenant(), a.channelAccount(), a.externalId()));
      // Step 2: The same external id on another channel account is fine.
      insertOrder(app, UUID.randomUUID(), a.tenant(), secondAccount, a.externalId());
      // Step 3: A blank external id is rejected.
      assertCheck(
          app,
          "sales_order_external_order_id_check",
          () -> insertOrder(app, UUID.randomUUID(), a.tenant(), secondAccount, " "));
      app.rollback();
    }
  }

  static Stream<Arguments> enumColumns() {
    return Stream.of(
        enumCase("sales_order", "order_status", "sales_order_order_status_check"),
        enumCase("sales_order", "payment_status", "sales_order_payment_status_check"),
        enumCase("sales_order", "fulfillment_status", "sales_order_fulfillment_status_check"),
        enumCase("sales_order", "hold_reason", "sales_order_hold_reason_check"),
        enumCase("sales_order", "payment_method", "sales_order_payment_method_check"),
        enumCase("sales_order", "currency", "sales_order_currency_check"),
        enumCase("order_recipient", "pii_status", "order_recipient_pii_status_check"),
        enumCase("shipment", "status", "shipment_status_check"),
        enumCase("return_request", "type", "return_request_type_check"),
        enumCase("return_request", "status", "return_request_status_check"),
        enumCase("return_line", "condition", "return_line_condition_check"),
        enumCase("refund", "status", "refund_status_check"),
        enumCase("refund", "currency", "refund_currency_check"),
        enumCase("sync_cursor", "resource", "sync_cursor_resource_check"),
        enumCase("shadow_diff", "kind", "shadow_diff_kind_check"),
        enumCase("reconciliation_issue", "status", "reconciliation_issue_status_check"),
        // Append-only tables cannot be updated, so the bad value goes in with an INSERT.
        Arguments.of(
            "order_status_history.dimension",
            "order_status_history_dimension_check",
            (GraphAction)
                (app, g) -> insertHistory(app, g.tenant(), g.order(), "SHIPPING", null, "ACTIVE")),
        Arguments.of(
            "order_status_history.to_value",
            "order_status_history_value_check",
            (GraphAction)
                (app, g) -> insertHistory(app, g.tenant(), g.order(), "ORDER", null, "PAID")),
        Arguments.of(
            "order_status_history.from_value",
            "order_status_history_value_check",
            (GraphAction)
                (app, g) -> insertHistory(app, g.tenant(), g.order(), "HOLD", "PACKED", "NONE")),
        Arguments.of(
            "payment_status_snapshot.provider",
            "payment_status_snapshot_provider_check",
            (GraphAction) (app, g) -> insertSnapshot(app, g.tenant(), g.order(), "STRIPE", "e-1")),
        Arguments.of(
            "payment_status_snapshot.currency",
            "payment_status_snapshot_currency_check",
            (GraphAction)
                (app, g) ->
                    insert(
                        app,
                        "INSERT INTO payment_status_snapshot (id, tenant_id, order_id, provider, "
                            + "status, amount, currency, observed_at, source_event_id) "
                            + "VALUES (?, ?, ?, 'XENDIT', 'PAID', 1, 'USD', now(), 'e-2')",
                        UUID.randomUUID(),
                        g.tenant(),
                        g.order())));
  }

  private static Arguments enumCase(String table, String column, String constraint) {
    return Arguments.of(
        table + "." + column,
        constraint,
        (GraphAction)
            (app, g) ->
                update(
                    app,
                    "UPDATE " + table + " SET " + column + " = 'BOGUS' WHERE tenant_id = ?",
                    g.tenant()));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("enumColumns")
  void everyEnumCheckRejectsABadValue(String label, String constraint, GraphAction action)
      throws SQLException {
    Graph a;
    try (Connection admin = openAdmin()) {
      a = seed(admin);
    }
    try (Connection app = openApp()) {
      app.setAutoCommit(false);
      setTenant(app, a.tenant());
      assertCheck(app, constraint, () -> action.run(app, a));
      app.rollback();
    }
  }

  @Test
  void otherValueChecksHold() throws SQLException {
    Graph a;
    try (Connection admin = openAdmin()) {
      a = seed(admin);
    }
    try (Connection app = openApp()) {
      app.setAutoCommit(false);
      setTenant(app, a.tenant());
      // Step 1: Quantities and money.
      assertCheck(
          app,
          "order_line_qty_check",
          () -> insertLine(app, UUID.randomUUID(), a.tenant(), a.bareOrder(), a.sku(), 0));
      assertCheck(
          app,
          "sales_order_amounts_check",
          () -> update(app, "UPDATE sales_order SET discount = -1 WHERE id = ?", a.order()));
      assertCheck(
          app,
          "return_line_qty_check",
          () -> update(app, "UPDATE return_line SET qty = 0 WHERE id = ?", a.returnLine()));
      assertCheck(
          app,
          "return_line_restocked_qty_check",
          () ->
              update(app, "UPDATE return_line SET restocked_qty = 2 WHERE id = ?", a.returnLine()));
      assertCheck(
          app,
          "return_line_restocked_qty_check",
          () ->
              update(
                  app, "UPDATE return_line SET restocked_qty = -1 WHERE id = ?", a.returnLine()));
      assertCheck(
          app,
          "refund_amount_check",
          () -> update(app, "UPDATE refund SET amount = -0.01 WHERE tenant_id = ?", a.tenant()));
      assertCheck(
          app,
          "payment_status_snapshot_amounts_check",
          () ->
              insert(
                  app,
                  "INSERT INTO payment_status_snapshot (id, tenant_id, order_id, provider, status, "
                      + "amount, refunded_amount, observed_at, source_event_id) "
                      + "VALUES (?, ?, ?, 'OPN', 'PAID', 10, -1, now(), 'e-neg')",
                  UUID.randomUUID(),
                  a.tenant(),
                  a.order()));
      assertThat(
              update(app, "UPDATE return_line SET restocked_qty = 1 WHERE id = ?", a.returnLine()))
          .isEqualTo(1);

      // Step 2: One shipment per order.
      assertSqlState(
          app,
          "23505",
          "shipment_order_id_key",
          () -> insertShipment(app, UUID.randomUUID(), a.tenant(), a.order(), a.warehouse()));

      // Step 3: Recipient PII status invariants. REDACTED means no ciphertext left; ACTIVE needs
      // name and address; a phone ciphertext comes with its hash.
      assertCheck(
          app,
          "order_recipient_redacted_check",
          () ->
              update(
                  app,
                  "UPDATE order_recipient SET pii_status = 'REDACTED', phone_enc = NULL, "
                      + "address_enc = NULL, phone_hash = NULL, phone_last4 = NULL "
                      + "WHERE order_id = ?",
                  a.order()));
      assertCheck(
          app,
          "order_recipient_active_check",
          () ->
              update(
                  app, "UPDATE order_recipient SET name_enc = NULL WHERE order_id = ?", a.order()));
      assertCheck(
          app,
          "order_recipient_phone_check",
          () ->
              update(
                  app,
                  "UPDATE order_recipient SET phone_hash = NULL WHERE order_id = ?",
                  a.order()));
      assertCheck(
          app,
          "order_recipient_phone_check",
          () ->
              update(
                  app,
                  "UPDATE order_recipient SET name_enc = NULL, phone_enc = NULL, "
                      + "address_enc = NULL, pii_status = 'REDACTED' WHERE order_id = ?",
                  a.order()));
      assertCheck(
          app,
          "order_recipient_phone_check",
          () ->
              update(
                  app,
                  "UPDATE order_recipient SET name_enc = NULL, phone_enc = NULL, "
                      + "address_enc = NULL, phone_hash = NULL, pii_status = 'REDACTED' "
                      + "WHERE order_id = ?",
                  a.order()));
      assertCheck(
          app,
          "order_recipient_phone_hash_check",
          () ->
              update(
                  app,
                  "UPDATE order_recipient SET phone_hash = '\\x01'::bytea WHERE order_id = ?",
                  a.order()));
      assertCheck(
          app,
          "order_recipient_phone_last4_check",
          () ->
              update(
                  app,
                  "UPDATE order_recipient SET phone_last4 = '12a4' WHERE order_id = ?",
                  a.order()));
      assertThat(
              update(
                  app,
                  "UPDATE order_recipient SET name_enc = NULL, phone_enc = NULL, "
                      + "address_enc = NULL, phone_hash = NULL, phone_last4 = NULL, "
                      + "pii_status = 'REDACTED' WHERE order_id = ?",
                  a.order()))
          .isEqualTo(1);
      app.rollback();
    }
  }

  @Test
  void recipientGoesWithItsOrderAndOtherChildrenRestrict() throws SQLException {
    Graph a;
    try (Connection admin = openAdmin()) {
      a = seed(admin);
    }
    try (Connection app = openApp()) {
      app.setAutoCommit(false);
      setTenant(app, a.tenant());
      // Step 1: An order with lines, history, and so on cannot be deleted (RESTRICT).
      assertSqlState(
          app,
          "23503",
          "foreign key",
          () -> update(app, "DELETE FROM sales_order WHERE id = ?", a.order()));
      // Step 2: An order with only a recipient deletes, and the PII row goes with it.
      insertRecipient(app, a.tenant(), a.bareOrder());
      assertThat(countWhere(app, "order_recipient", "order_id", a.bareOrder())).isEqualTo(1);
      assertThat(update(app, "DELETE FROM sales_order WHERE id = ?", a.bareOrder())).isEqualTo(1);
      assertThat(countWhere(app, "order_recipient", "order_id", a.bareOrder())).isZero();
      app.rollback();
    }
  }

  @Test
  void paymentEventIdsAreUniquePerTenant() throws SQLException {
    Graph a;
    Graph b;
    try (Connection admin = openAdmin()) {
      a = seed(admin);
      b = seed(admin);
    }
    try (Connection app = openApp()) {
      app.setAutoCommit(false);
      // Step 1: A second row with the same source_event_id in one tenant is rejected.
      setTenant(app, a.tenant());
      assertSqlState(
          app,
          "23505",
          "refund_tenant_source_event_key",
          () -> insertRefund(app, a.tenant(), a.order(), null, a.eventId()));
      assertSqlState(
          app,
          "23505",
          "payment_status_snapshot_tenant_source_event_key",
          () -> insertSnapshot(app, a.tenant(), a.order(), "XENDIT", a.eventId()));
      app.commit();
      // Step 2: Another tenant may reuse A's event id (ids are only unique per shop).
      setTenant(app, b.tenant());
      insertRefund(app, b.tenant(), b.order(), null, a.eventId());
      insertSnapshot(app, b.tenant(), b.order(), "XENDIT", a.eventId());
      app.rollback();

      // Step 3: A refund's return must be a return of the same order.
      setTenant(app, a.tenant());
      assertSqlState(
          app,
          "23503",
          "refund_return_fkey",
          () -> insertRefund(app, a.tenant(), a.bareOrder(), a.returnRequest(), "evt-other"));
      app.rollback();
    }
  }

  @Test
  void openReconciliationIssueIsUniqueIncludingShopLevel() throws SQLException {
    Graph a;
    try (Connection admin = openAdmin()) {
      a = seed(admin);
    }
    try (Connection app = openApp()) {
      app.setAutoCommit(false);
      setTenant(app, a.tenant());
      // Step 1: One open issue per (rule, order). ACK still counts as open.
      assertSqlState(
          app,
          "23505",
          "reconciliation_issue_open_key",
          () -> insertIssue(app, a.tenant(), "rule-1", a.order(), "ACK"));
      // Step 2: Shop-level (order_id null): NULLS NOT DISTINCT allows only one open per rule.
      UUID shopLevel = insertIssue(app, a.tenant(), "shop-rule", null, "OPEN");
      assertSqlState(
          app,
          "23505",
          "reconciliation_issue_open_key",
          () -> insertIssue(app, a.tenant(), "shop-rule", null, "OPEN"));
      // Step 3: Once resolved, a new open issue for the same key is allowed.
      assertThat(
              update(
                  app,
                  "UPDATE reconciliation_issue SET status = 'RESOLVED' WHERE id = ?",
                  shopLevel))
          .isEqualTo(1);
      insertIssue(app, a.tenant(), "shop-rule", null, "OPEN");
      insertIssue(app, a.tenant(), "shop-rule", null, "RESOLVED");
      app.rollback();
    }
  }

  @Test
  void appendOnlyTablesRejectUpdateDeleteAndTruncate() throws SQLException {
    Graph a;
    try (Connection admin = openAdmin()) {
      a = seed(admin);
      // Step 1: The superuser is rejected by the trigger on UPDATE, DELETE, and TRUNCATE.
      admin.setAutoCommit(false);
      for (String table : APPEND_ONLY) {
        for (String sql :
            List.of(
                "UPDATE " + table + " SET tenant_id = tenant_id",
                "DELETE FROM " + table,
                "TRUNCATE TABLE " + table)) {
          assertSqlState(admin, "P0001", table + " is append-only", () -> execute(admin, sql), sql);
        }
      }
      admin.rollback();
    }

    // Step 2: The table owner is rejected too.
    try (Connection owner = openMigrator()) {
      owner.setAutoCommit(false);
      setTenant(owner, a.tenant());
      for (String table : APPEND_ONLY) {
        assertThat(count(owner, table)).isEqualTo(1);
        assertSqlState(
            owner,
            "P0001",
            "append-only",
            () ->
                update(
                    owner,
                    "UPDATE " + table + " SET tenant_id = tenant_id WHERE tenant_id = ?",
                    a));
        assertSqlState(
            owner,
            "P0001",
            "append-only",
            () -> update(owner, "DELETE FROM " + table + " WHERE tenant_id = ?", a));
      }
      owner.rollback();
    }

    // Step 3: oms_app appends, and has no UPDATE or DELETE privilege.
    try (Connection app = openApp()) {
      app.setAutoCommit(false);
      setTenant(app, a.tenant());
      insertHistory(app, a.tenant(), a.order(), "PAYMENT");
      insertSnapshot(app, a.tenant(), a.order(), "OPN", "evt-" + UUID.randomUUID());
      for (String table : APPEND_ONLY) {
        assertSqlState(
            app,
            "42501",
            "permission denied",
            () -> update(app, "DELETE FROM " + table + " WHERE tenant_id = ?", a));
      }
      app.commit();
    }
    try (Connection admin = openAdmin()) {
      for (String table : APPEND_ONLY) {
        assertThat(countFor(admin, table, a.tenant())).as(table).isEqualTo(2);
      }
    }
  }

  @Test
  void returnQuantityIsLimitedByTheOrderLine() throws SQLException {
    Graph a;
    try (Connection admin = openAdmin()) {
      a = seed(admin);
    }
    // The seeded order line has qty 2 and a REQUESTED return of 1.
    try (Connection app = openApp()) {
      app.setAutoCommit(false);
      setTenant(app, a.tenant());
      UUID second = UUID.randomUUID();
      insertReturn(app, second, a.tenant(), a.order(), "REQUESTED");

      // Step 1: 1 + 2 > 2 is rejected. 1 + 1 fits.
      assertReturnQtyExceeded(
          app,
          () ->
              insertReturnLine(app, UUID.randomUUID(), a.tenant(), a.order(), second, a.line(), 2));
      UUID secondLine = UUID.randomUUID();
      insertReturnLine(app, secondLine, a.tenant(), a.order(), second, a.line(), 1);

      // Step 2: Raising an existing return line past the order qty is rejected.
      assertReturnQtyExceeded(
          app, () -> update(app, "UPDATE return_line SET qty = 2 WHERE id = ?", secondLine));

      // Step 3: Lines of a REJECTED request do not count. Rejecting frees the qty.
      assertThat(update(app, "UPDATE return_request SET status = 'REJECTED' WHERE id = ?", second))
          .isEqualTo(1);
      UUID third = UUID.randomUUID();
      UUID thirdLine = UUID.randomUUID();
      insertReturn(app, third, a.tenant(), a.order(), "APPROVED");
      insertReturnLine(app, thirdLine, a.tenant(), a.order(), third, a.line(), 1);
      // A line of the REJECTED request can grow: it is not counted either.
      assertThat(update(app, "UPDATE return_line SET qty = 2 WHERE id = ?", secondLine))
          .isEqualTo(1);

      // Step 4: Closing the rejected request does not make it count again (still 1 + 1).
      assertThat(update(app, "UPDATE return_request SET status = 'CLOSED' WHERE id = ?", second))
          .isEqualTo(1);
      assertThat(countedReturnQty(app, a.line())).isEqualTo(2);

      // Step 5: order_line.qty cannot drop below the returned qty (now 2). Raising is fine.
      assertReturnQtyExceeded(
          app, () -> update(app, "UPDATE order_line SET qty = 1 WHERE id = ?", a.line()));
      assertThat(update(app, "UPDATE order_line SET qty = 5 WHERE id = ?", a.line())).isEqualTo(1);
      assertThat(update(app, "UPDATE order_line SET qty = 2 WHERE id = ?", a.line())).isEqualTo(1);

      // Step 6: Moving a return line to another order line is checked against the new line.
      UUID otherLine = UUID.randomUUID();
      insertLine(app, otherLine, a.tenant(), a.order(), a.sku(), 1);
      assertReturnQtyExceeded(
          app,
          () ->
              update(
                  app,
                  "UPDATE return_line SET order_line_id = ?, qty = 2 WHERE id = ?",
                  otherLine,
                  thirdLine));
      assertThat(
              update(
                  app,
                  "UPDATE return_line SET order_line_id = ? WHERE id = ?",
                  otherLine,
                  thirdLine))
          .isEqualTo(1);
      app.rollback();
    }
  }

  @Test
  void rejectedReturnsNeverCountAgain() throws SQLException {
    Graph a;
    UUID line = UUID.randomUUID();
    try (Connection admin = openAdmin()) {
      a = seed(admin);
      insertLine(admin, line, a.tenant(), a.order(), a.sku(), 1);
    }
    try (Connection app = openApp()) {
      app.setAutoCommit(false);
      setTenant(app, a.tenant());

      // Step 1: R1 returns the only unit and is rejected. R2 returns the same unit again.
      UUID r1 = UUID.randomUUID();
      UUID r2 = UUID.randomUUID();
      insertReturn(app, r1, a.tenant(), a.order(), "REQUESTED");
      insertReturnLine(app, UUID.randomUUID(), a.tenant(), a.order(), r1, line, 1);
      assertThat(update(app, "UPDATE return_request SET status = 'REJECTED' WHERE id = ?", r1))
          .isEqualTo(1);
      assertThat(rejectedFlag(app, r1)).isTrue();
      insertReturn(app, r2, a.tenant(), a.order(), "REQUESTED");
      insertReturnLine(app, UUID.randomUUID(), a.tenant(), a.order(), r2, line, 1);

      // Step 2: Closing R1 (the normal REJECTED -> CLOSED) succeeds, and R1 still does not count.
      assertThat(update(app, "UPDATE return_request SET status = 'CLOSED' WHERE id = ?", r1))
          .isEqualTo(1);
      assertThat(rejectedFlag(app, r1)).isTrue();
      assertThat(countedReturnQty(app, line)).isEqualTo(1);
      app.rollback();

      // Step 3: The other order. R1 is rejected and closed first, then R2 still fits.
      setTenant(app, a.tenant());
      insertReturn(app, r1, a.tenant(), a.order(), "REJECTED");
      insertReturnLine(app, UUID.randomUUID(), a.tenant(), a.order(), r1, line, 1);
      assertThat(rejectedFlag(app, r1)).isTrue();
      assertThat(update(app, "UPDATE return_request SET status = 'CLOSED' WHERE id = ?", r1))
          .isEqualTo(1);
      insertReturn(app, r2, a.tenant(), a.order(), "APPROVED");
      insertReturnLine(app, UUID.randomUUID(), a.tenant(), a.order(), r2, line, 1);
      assertThat(countedReturnQty(app, line)).isEqualTo(1);

      // Step 4: A rejected request cannot move anywhere but CLOSED, even after closing.
      for (String status : List.of("APPROVED", "REQUESTED", "RECEIVED")) {
        assertRejectedFinal(
            app,
            "return_request_status_transition",
            () -> update(app, "UPDATE return_request SET status = ? WHERE id = ?", status, r1));
      }
      UUID r3 = UUID.randomUUID();
      insertReturn(app, r3, a.tenant(), a.order(), "REJECTED");
      assertRejectedFinal(
          app,
          "return_request_status_transition",
          () -> update(app, "UPDATE return_request SET status = 'APPROVED' WHERE id = ?", r3));

      // Step 5: rejected cannot be cleared, on its own or together with a status change.
      for (String sql :
          List.of(
              "UPDATE return_request SET rejected = false WHERE id = ?",
              "UPDATE return_request SET rejected = false, status = 'CLOSED' WHERE id = ?")) {
        assertRejectedFinal(app, "return_request_rejected_sticky", () -> update(app, sql, r3));
        assertRejectedFinal(app, "return_request_rejected_sticky", () -> update(app, sql, r1));
      }

      // Step 6: rejected can only be set together with REJECTED or CLOSED.
      assertCheck(
          app,
          "return_request_rejected_check",
          () ->
              update(
                  app,
                  "UPDATE return_request SET rejected = true WHERE id = ?",
                  a.returnRequest()));
      app.rollback();
    }
  }

  private static long countedReturnQty(Connection connection, UUID orderLineId)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT coalesce(sum(rl.qty), 0) FROM return_line rl "
                + "JOIN return_request rr ON rr.id = rl.return_id "
                + "WHERE rl.order_line_id = ? AND NOT rr.rejected")) {
      statement.setObject(1, orderLineId);
      return single(statement);
    }
  }

  private static boolean rejectedFlag(Connection connection, UUID returnId) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement("SELECT rejected FROM return_request WHERE id = ?")) {
      statement.setObject(1, returnId);
      try (ResultSet rows = statement.executeQuery()) {
        assertThat(rows.next()).isTrue();
        return rows.getBoolean(1);
      }
    }
  }

  private static void assertRejectedFinal(
      Connection connection, String constraint, SqlAction action) throws SQLException {
    PSQLException exception =
        assertSqlState(connection, "23514", "RETURN_REJECTED_FINAL:", action, constraint);
    assertThat(exception.getServerErrorMessage().getMessage()).startsWith("RETURN_REJECTED_FINAL:");
    assertThat(exception.getServerErrorMessage().getConstraint()).isEqualTo(constraint);
  }

  @Test
  void returnLineMustBelongToTheSameOrder() throws SQLException {
    Graph a;
    try (Connection admin = openAdmin()) {
      a = seed(admin);
    }
    try (Connection app = openApp()) {
      app.setAutoCommit(false);
      setTenant(app, a.tenant());
      UUID foreignLine = UUID.randomUUID();
      insertLine(app, foreignLine, a.tenant(), a.bareOrder(), a.sku(), 3);

      // Step 1: The request is for a.order(); the order line belongs to a.bareOrder().
      assertSqlState(
          app,
          "23503",
          "return_line_order_line_fkey",
          () ->
              insertReturnLine(
                  app,
                  UUID.randomUUID(),
                  a.tenant(),
                  a.order(),
                  a.returnRequest(),
                  foreignLine,
                  1));
      assertSqlState(
          app,
          "23503",
          "return_line_return_fkey",
          () ->
              insertReturnLine(
                  app,
                  UUID.randomUUID(),
                  a.tenant(),
                  a.bareOrder(),
                  a.returnRequest(),
                  foreignLine,
                  1));

      // Step 2: A request or a line with return lines cannot move to another order.
      assertSqlState(
          app,
          "23503",
          "return_line_return_fkey",
          () ->
              update(
                  app,
                  "UPDATE return_request SET order_id = ? WHERE id = ?",
                  a.bareOrder(),
                  a.returnRequest()));
      assertSqlState(
          app,
          "23503",
          "return_line_order_line_fkey",
          () ->
              update(
                  app, "UPDATE order_line SET order_id = ? WHERE id = ?", a.bareOrder(), a.line()));
      app.rollback();
    }
  }

  @Test
  void returnChecksRequireReadCommitted() throws SQLException {
    Graph a;
    UUID rejected = UUID.randomUUID();
    try (Connection admin = openAdmin()) {
      a = seed(admin);
      insertReturn(admin, rejected, a.tenant(), a.order(), "REJECTED");
    }
    try (Connection app = openApp()) {
      app.setAutoCommit(false);
      for (int level :
          List.of(Connection.TRANSACTION_REPEATABLE_READ, Connection.TRANSACTION_SERIALIZABLE)) {
        app.setTransactionIsolation(level);
        setTenant(app, a.tenant());
        // Step 1: A return line write and lowering order_line.qty are refused.
        Map<String, SqlAction> writes = new LinkedHashMap<>();
        writes.put(
            "return_line insert",
            () ->
                insertReturnLine(
                    app, UUID.randomUUID(), a.tenant(), a.order(), rejected, a.line(), 1));
        writes.put(
            "return_line qty",
            () -> update(app, "UPDATE return_line SET qty = 1 WHERE id = ?", a.returnLine()));
        writes.put(
            "order_line qty",
            () -> update(app, "UPDATE order_line SET qty = 1 WHERE id = ?", a.line()));
        for (Map.Entry<String, SqlAction> write : writes.entrySet()) {
          PSQLException exception =
              assertSqlState(
                  app, "23514", "READ_COMMITTED_REQUIRED", write.getValue(), write.getKey());
          assertThat(exception.getServerErrorMessage().getMessage())
              .startsWith("READ_COMMITTED_REQUIRED:");
          assertThat(exception.getServerErrorMessage().getConstraint())
              .isEqualTo("return_line_isolation");
        }
        // Step 2: Writes that cannot break the limit are unaffected by the level, including
        // closing a rejected request (it never counts again, so nothing is re-summed).
        assertThat(update(app, "UPDATE order_line SET qty = 3 WHERE id = ?", a.line()))
            .isEqualTo(1);
        assertThat(
                update(app, "UPDATE return_request SET status = 'CLOSED' WHERE id = ?", rejected))
            .isEqualTo(1);
        assertThat(
                update(
                    app,
                    "UPDATE return_line SET condition = 'DAMAGED' WHERE id = ?",
                    a.returnLine()))
            .isEqualTo(1);
        app.rollback();
      }
      app.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
    }
  }

  @Test
  void concurrentReturnsOfTheLastUnitCommitOnlyOnce() throws Exception {
    Graph a;
    UUID line = UUID.randomUUID();
    UUID first = UUID.randomUUID();
    UUID second = UUID.randomUUID();
    try (Connection admin = openAdmin()) {
      a = seed(admin);
      insertLine(admin, line, a.tenant(), a.order(), a.sku(), 1);
      insertReturn(admin, first, a.tenant(), a.order(), "REQUESTED");
      insertReturn(admin, second, a.tenant(), a.order(), "REQUESTED");
    }

    // Step 1: Two transactions each return qty 1 of the qty-1 line, released at the same time.
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      CountDownLatch ready = new CountDownLatch(2);
      CountDownLatch go = new CountDownLatch(1);
      List<Future<String>> results = new ArrayList<>();
      for (UUID request : List.of(first, second)) {
        results.add(
            pool.submit(
                () -> {
                  try (Connection app = openApp()) {
                    app.setAutoCommit(false);
                    setTenant(app, a.tenant());
                    ready.countDown();
                    go.await(10, TimeUnit.SECONDS);
                    try {
                      insertReturnLine(
                          app, UUID.randomUUID(), a.tenant(), a.order(), request, line, 1);
                      app.commit();
                      return "COMMITTED";
                    } catch (PSQLException ex) {
                      app.rollback();
                      return ex.getServerErrorMessage().getMessage();
                    }
                  }
                }));
      }
      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      go.countDown();
      List<String> outcomes = new ArrayList<>();
      for (Future<String> result : results) {
        outcomes.add(result.get(30, TimeUnit.SECONDS));
      }

      // Step 2: Exactly one commits. The other waited on the order line lock, then saw it.
      assertThat(outcomes).filteredOn("COMMITTED"::equals).hasSize(1);
      assertThat(outcomes)
          .filteredOn(outcome -> outcome.startsWith("RETURN_QTY_EXCEEDED:"))
          .hasSize(1);
    } finally {
      pool.shutdownNow();
    }
    try (Connection admin = openAdmin()) {
      assertThat(countWhere(admin, "return_line", "order_line_id", line)).isEqualTo(1);
    }
  }

  @Test
  void returnWritesWaitForEachOtherOnTheOrderLine() throws Exception {
    Graph a;
    UUID line = UUID.randomUUID();
    UUID open = UUID.randomUUID();
    UUID other = UUID.randomUUID();
    UUID rejected = UUID.randomUUID();
    try (Connection admin = openAdmin()) {
      a = seed(admin);
      insertLine(admin, line, a.tenant(), a.order(), a.sku(), 1);
      insertReturn(admin, open, a.tenant(), a.order(), "REQUESTED");
      insertReturn(admin, other, a.tenant(), a.order(), "REQUESTED");
      insertReturn(admin, rejected, a.tenant(), a.order(), "REJECTED");
      insertReturnLine(admin, UUID.randomUUID(), a.tenant(), a.order(), rejected, line, 1);
    }
    try (Connection left = openApp();
        Connection right = openApp()) {
      left.setAutoCommit(false);
      right.setAutoCommit(false);

      // Step 1: An uncommitted return line (another request) holds the order line. A second
      // return line on it waits. The rejected request's line does not count.
      setTenant(left, a.tenant());
      insertReturnLine(left, UUID.randomUUID(), a.tenant(), a.order(), other, line, 1);
      setTenant(right, a.tenant());
      lockTimeout(right);
      assertSqlState(
          right,
          "55P03",
          "lock",
          () -> insertReturnLine(right, UUID.randomUUID(), a.tenant(), a.order(), open, line, 1));
      right.rollback();

      // Step 2: After it commits, the waiter sees the counted line and is rejected.
      left.commit();
      setTenant(right, a.tenant());
      assertReturnQtyExceeded(
          right,
          () -> insertReturnLine(right, UUID.randomUUID(), a.tenant(), a.order(), open, line, 1));
      right.rollback();

      // Step 3: The other order. An uncommitted return line blocks lowering the line qty.
      setTenant(left, a.tenant());
      update(left, "UPDATE order_line SET qty = 2 WHERE id = ?", line);
      left.commit();
      setTenant(left, a.tenant());
      insertReturnLine(left, UUID.randomUUID(), a.tenant(), a.order(), open, line, 1);
      setTenant(right, a.tenant());
      lockTimeout(right);
      assertSqlState(
          right,
          "55P03",
          "lock",
          () -> update(right, "UPDATE order_line SET qty = 1 WHERE id = ?", line));
      right.rollback();
      left.commit();
      setTenant(right, a.tenant());
      assertReturnQtyExceeded(
          right, () -> update(right, "UPDATE order_line SET qty = 1 WHERE id = ?", line));
      right.rollback();
    }
  }

  private Connection openAdmin() throws SQLException {
    return DriverManager.getConnection(
        postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
  }

  private static Connection openApp() throws SQLException {
    return DriverManager.getConnection(postgres.getJdbcUrl(), "oms_app", APP_PASSWORD);
  }

  private Connection openMigrator() throws SQLException {
    return DriverManager.getConnection(postgres.getJdbcUrl(), "oms_migrator", MIGRATOR_PASSWORD);
  }

  private static List<String> versions(Connection connection) throws SQLException {
    List<String> versions = new ArrayList<>();
    try (Statement statement = connection.createStatement();
        ResultSet rows =
            statement.executeQuery(
                "SELECT version, success FROM flyway_schema_history ORDER BY installed_rank")) {
      while (rows.next()) {
        assertThat(rows.getBoolean("success")).isTrue();
        versions.add(rows.getString("version"));
      }
    }
    return versions;
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

  private static void exec(Connection connection, String sql, Object... params)
      throws SQLException {
    update(connection, sql, params);
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
    return countWhere(connection, table, "tenant_id", tenantId);
  }

  private static long countWhere(Connection connection, String table, String column, UUID value)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT count(*) FROM " + table + " WHERE " + column + " = ?")) {
      statement.setObject(1, value);
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

  private static PSQLException assertReturnQtyExceeded(Connection connection, SqlAction action)
      throws SQLException {
    PSQLException exception =
        assertSqlState(connection, "23514", "RETURN_QTY_EXCEEDED:", action, "RETURN_QTY_EXCEEDED");
    assertThat(exception.getServerErrorMessage().getMessage()).startsWith("RETURN_QTY_EXCEEDED:");
    assertThat(exception.getServerErrorMessage().getConstraint())
        .isEqualTo("return_line_qty_limit");
    return exception;
  }

  private static Graph seed(Connection connection) throws SQLException {
    UUID tenant = UUID.randomUUID();
    insertTenant(connection, tenant);
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
            "EXT-" + tenant,
            "evt-" + tenant);
    insertChannelAccount(connection, graph.channelAccount(), tenant);
    insertProductSku(connection, tenant, UUID.randomUUID(), graph.sku());
    insertWarehouse(connection, graph.warehouse(), tenant);
    insertOrder(connection, graph.order(), tenant, graph.channelAccount(), graph.externalId());
    insertOrder(
        connection, graph.bareOrder(), tenant, graph.channelAccount(), graph.externalId() + "-2");
    insertRecipient(connection, tenant, graph.order());
    insertLine(connection, graph.line(), tenant, graph.order(), graph.sku(), 2);
    insertHistory(connection, tenant, graph.order(), "ORDER");
    insertShipment(connection, UUID.randomUUID(), tenant, graph.order(), graph.warehouse());
    insertReturn(connection, graph.returnRequest(), tenant, graph.order(), "REQUESTED");
    insertReturnLine(
        connection,
        graph.returnLine(),
        tenant,
        graph.order(),
        graph.returnRequest(),
        graph.line(),
        1);
    insertRefund(connection, tenant, graph.order(), graph.returnRequest(), graph.eventId());
    insertSnapshot(connection, tenant, graph.order(), "XENDIT", graph.eventId());
    insertCursor(connection, tenant, graph.channelAccount(), "ORDERS");
    insertShadowDiff(connection, tenant, graph.channelAccount(), "STOCK");
    insertIssue(connection, tenant, "rule-1", graph.order(), "OPEN");
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

  private static void insertChannelAccount(Connection connection, UUID id, UUID tenantId)
      throws SQLException {
    insert(
        connection,
        "INSERT INTO channel_account (id, tenant_id, channel, external_shop_id, status) "
            + "VALUES (?, ?, 'TSF', ?, 'CONNECTED')",
        id,
        tenantId,
        "shop-" + id);
  }

  private static void insertProductSku(
      Connection connection, UUID tenantId, UUID productId, UUID skuId) throws SQLException {
    insert(
        connection,
        "INSERT INTO product (id, tenant_id, name, status) VALUES (?, ?, 'Product', 'ACTIVE')",
        productId,
        tenantId);
    insert(
        connection,
        "INSERT INTO sku (id, tenant_id, product_id, sku_code, name) VALUES (?, ?, ?, ?, 'SKU')",
        skuId,
        tenantId,
        productId,
        "SKU-" + skuId);
  }

  private static void insertWarehouse(Connection connection, UUID id, UUID tenantId)
      throws SQLException {
    insert(
        connection,
        "INSERT INTO warehouse (id, tenant_id, code, name) VALUES (?, ?, ?, 'Main')",
        id,
        tenantId,
        "WH-" + id);
  }

  private static void insertOrder(
      Connection connection, UUID id, UUID tenantId, UUID channelAccountId, String externalId)
      throws SQLException {
    insert(
        connection,
        "INSERT INTO sales_order (id, tenant_id, channel_account_id, external_order_id, "
            + "payment_status, payment_method, subtotal, grand_total, ordered_at) "
            + "VALUES (?, ?, ?, ?, 'PENDING', 'PREPAID', 590.00, 580.00, now())",
        id,
        tenantId,
        channelAccountId,
        externalId);
  }

  // Placeholder ciphertext. The schema only sees bytea; PiiCipher is tested on its own.
  private static void insertRecipient(Connection connection, UUID tenantId, UUID orderId)
      throws SQLException {
    insert(
        connection,
        "INSERT INTO order_recipient (order_id, tenant_id, name_enc, phone_enc, phone_hash, "
            + "phone_last4, address_enc, province, postcode) "
            + "VALUES (?, ?, '\\x01'::bytea, '\\x01'::bytea, decode(repeat('ab', 32), 'hex'), "
            + "'1234', '\\x01'::bytea, 'Bangkok', '10110')",
        orderId,
        tenantId);
  }

  private static void insertLine(
      Connection connection, UUID id, UUID tenantId, UUID orderId, UUID skuId, int qty)
      throws SQLException {
    insert(
        connection,
        "INSERT INTO order_line (id, tenant_id, order_id, sku_id, external_line_id, name, qty, "
            + "unit_price, line_total) VALUES (?, ?, ?, ?, ?, 'Line', ?, 295.00, 590.00)",
        id,
        tenantId,
        orderId,
        skuId,
        "L-" + id,
        qty);
  }

  private static void insertHistory(
      Connection connection, UUID tenantId, UUID orderId, String dimension) throws SQLException {
    String to =
        switch (dimension) {
          case "ORDER" -> "ACTIVE";
          case "PAYMENT" -> "PENDING";
          case "FULFILLMENT" -> "UNFULFILLED";
          default -> "NONE";
        };
    insertHistory(connection, tenantId, orderId, dimension, null, to);
  }

  private static void insertHistory(
      Connection connection, UUID tenantId, UUID orderId, String dimension, String from, String to)
      throws SQLException {
    insert(
        connection,
        "INSERT INTO order_status_history (id, tenant_id, order_id, dimension, from_value, "
            + "to_value, reason, actor) VALUES (?, ?, ?, ?, ?, ?, 'test', 'SYSTEM')",
        UUID.randomUUID(),
        tenantId,
        orderId,
        dimension,
        from,
        to);
  }

  private static void insertShipment(
      Connection connection, UUID id, UUID tenantId, UUID orderId, UUID warehouseId)
      throws SQLException {
    insert(
        connection,
        "INSERT INTO shipment (id, tenant_id, order_id, warehouse_id, carrier) "
            + "VALUES (?, ?, ?, ?, 'FLASH')",
        id,
        tenantId,
        orderId,
        warehouseId);
  }

  private static void insertReturn(
      Connection connection, UUID id, UUID tenantId, UUID orderId, String status)
      throws SQLException {
    insert(
        connection,
        "INSERT INTO return_request (id, tenant_id, order_id, external_return_id, type, status) "
            + "VALUES (?, ?, ?, ?, 'RETURN', ?)",
        id,
        tenantId,
        orderId,
        "ret-" + id,
        status);
  }

  private static void insertReturnLine(
      Connection connection,
      UUID id,
      UUID tenantId,
      UUID orderId,
      UUID returnId,
      UUID orderLineId,
      int qty)
      throws SQLException {
    insert(
        connection,
        "INSERT INTO return_line (id, tenant_id, order_id, return_id, order_line_id, qty) "
            + "VALUES (?, ?, ?, ?, ?, ?)",
        id,
        tenantId,
        orderId,
        returnId,
        orderLineId,
        qty);
  }

  private static void insertRefund(
      Connection connection, UUID tenantId, UUID orderId, UUID returnId, String eventId)
      throws SQLException {
    insert(
        connection,
        "INSERT INTO refund (id, tenant_id, order_id, return_id, provider_ref, amount, status, "
            + "observed_at, source_event_id) VALUES (?, ?, ?, ?, 'xnd_rf_1', 295.00, 'PENDING', "
            + "now(), ?)",
        UUID.randomUUID(),
        tenantId,
        orderId,
        returnId,
        eventId);
  }

  private static void insertSnapshot(
      Connection connection, UUID tenantId, UUID orderId, String provider, String eventId)
      throws SQLException {
    insert(
        connection,
        "INSERT INTO payment_status_snapshot (id, tenant_id, order_id, provider, provider_ref, "
            + "status, amount, observed_at, source_event_id) "
            + "VALUES (?, ?, ?, ?, 'pay_1', 'PAID', 580.00, now(), ?)",
        UUID.randomUUID(),
        tenantId,
        orderId,
        provider,
        eventId);
  }

  private static void insertCursor(
      Connection connection, UUID tenantId, UUID channelAccountId, String resource)
      throws SQLException {
    insert(
        connection,
        "INSERT INTO sync_cursor (tenant_id, channel_account_id, resource, cursor) "
            + "VALUES (?, ?, ?, 'c-1')",
        tenantId,
        channelAccountId,
        resource);
  }

  private static void insertShadowDiff(
      Connection connection, UUID tenantId, UUID channelAccountId, String kind)
      throws SQLException {
    insert(
        connection,
        "INSERT INTO shadow_diff (id, tenant_id, channel_account_id, kind, ref, oms_value, "
            + "channel_value, observed_at) VALUES (?, ?, ?, ?, 'listing-1', '5'::jsonb, "
            + "'4'::jsonb, now())",
        UUID.randomUUID(),
        tenantId,
        channelAccountId,
        kind);
  }

  private static UUID insertIssue(
      Connection connection, UUID tenantId, String rule, UUID orderId, String status)
      throws SQLException {
    UUID id = UUID.randomUUID();
    insert(
        connection,
        "INSERT INTO reconciliation_issue (id, tenant_id, run_id, rule, order_id, details, "
            + "status) VALUES (?, ?, ?, ?, ?, '{}'::jsonb, ?)",
        id,
        tenantId,
        UUID.randomUUID(),
        rule,
        orderId,
        status);
    return id;
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

  @FunctionalInterface
  interface GraphAction {
    void run(Connection app, Graph graph) throws SQLException;
  }

  record Graph(
      UUID tenant,
      UUID channelAccount,
      UUID sku,
      UUID warehouse,
      UUID order,
      UUID bareOrder,
      UUID line,
      UUID returnRequest,
      UUID returnLine,
      String externalId,
      String eventId) {}
}
