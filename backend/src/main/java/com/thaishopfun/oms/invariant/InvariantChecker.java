package com.thaishopfun.oms.invariant;

import com.thaishopfun.oms.order.OrderReservationCoverage;
import com.thaishopfun.oms.stock.ReserveItem;
import com.thaishopfun.oms.tenant.TenantContext;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Stock and order consistency checks under tenant RLS ({@code oms_app}). Tests call {@link
 * #checkAll()}; the nightly job calls {@link #checkTenant(UUID)} per active tenant plus {@link
 * #checkSchema()} once.
 */
@Component
public class InvariantChecker {

  private static final Logger log = LoggerFactory.getLogger(InvariantChecker.class);

  private static final int ENTITY_LIMIT = 20;

  private static final String ORDER_OWNER_UUID_PATTERN =
      "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$";

  private final JdbcTemplate jdbc;
  private final TransactionTemplate tenantReadTx;
  private final OrderReservationCoverage coverage;
  private final Clock clock;

  public InvariantChecker(
      JdbcTemplate jdbc,
      PlatformTransactionManager transactions,
      OrderReservationCoverage coverage,
      Clock clock) {
    this.jdbc = jdbc;
    this.coverage = coverage;
    this.clock = clock;
    this.tenantReadTx = new TransactionTemplate(transactions);
    this.tenantReadTx.setReadOnly(true);
    this.tenantReadTx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
  }

  public List<Violation> checkAll() {
    List<Violation> violations = new ArrayList<>(checkSchema());
    List<UUID> tenants =
        jdbc.query(
            "SELECT id FROM list_active_tenant_ids()", (rs, row) -> rs.getObject("id", UUID.class));
    for (UUID tenantId : tenants) {
      violations.addAll(checkTenant(tenantId));
    }
    return violations;
  }

  public List<Violation> checkTenant(UUID tenantId) {
    return runForTenant(tenantId, () -> checkTenantInContext(tenantId, true));
  }

  /** Stock invariants only (reservation engine tests without {@code sales_order} rows). */
  public List<Violation> checkTenantStock(UUID tenantId) {
    return runForTenant(tenantId, () -> checkTenantInContext(tenantId, false));
  }

  private List<Violation> runForTenant(
      UUID tenantId, java.util.function.Supplier<List<Violation>> work) {
    UUID previousTenant = TenantContext.tenantId();
    UUID previousUser = TenantContext.userId();
    try {
      TenantContext.set(tenantId, null);
      return tenantReadTx.execute(status -> work.get());
    } finally {
      if (previousTenant != null) {
        TenantContext.set(previousTenant, previousUser);
      } else {
        TenantContext.clear();
      }
    }
  }

  public List<Violation> checkSchema() {
    List<Violation> violations = new ArrayList<>();
    // Step 1: Every public table with tenant_id must have FORCE RLS and at least one policy.
    List<UUID> badTables =
        jdbc.query(
            """
            SELECT c.oid::regclass::text AS table_name
            FROM pg_class c
            JOIN pg_namespace n ON n.oid = c.relnamespace
            WHERE n.nspname = 'public'
              AND c.relkind = 'r'
              AND EXISTS (
                SELECT 1 FROM pg_attribute a
                WHERE a.attrelid = c.oid AND a.attname = 'tenant_id' AND NOT a.attisdropped
              )
              AND (
                NOT c.relrowsecurity OR NOT c.relforcerowsecurity
                OR NOT EXISTS (
                  SELECT 1 FROM pg_policies p WHERE p.schemaname = 'public' AND p.tablename = c.relname
                )
              )
            ORDER BY 1
            LIMIT ?
            """,
            (rs, row) -> UUID.nameUUIDFromBytes(rs.getString("table_name").getBytes()),
            ENTITY_LIMIT);
    if (!badTables.isEmpty()) {
      violations.add(Violation.schema(InvariantCodes.SCHEMA_FORCE_RLS, badTables));
    }
    // Step 2: oms_app must not bypass RLS or be superuser.
    List<Boolean> badRole =
        jdbc.query(
            """
            SELECT 1 FROM pg_roles
            WHERE rolname = 'oms_app' AND (rolsuper OR rolbypassrls)
            """,
            (rs, row) -> true);
    if (!badRole.isEmpty()) {
      violations.add(Violation.schema(InvariantCodes.SCHEMA_OMS_APP_ROLE, List.of()));
    }
    return violations;
  }

  private List<Violation> checkTenantInContext(UUID tenantId, boolean includeOrders) {
    List<Violation> violations = new ArrayList<>();
    violations.addAll(stockReservedBounds(tenantId));
    violations.addAll(stockActiveReservationMismatch(tenantId));
    violations.addAll(stockLedgerMismatch(tenantId));
    violations.addAll(stockReservationOwnerSplit(tenantId));
    if (includeOrders) {
      logMalformedOrderOwnerRefs(tenantId);
      violations.addAll(orderReservationOrphan(tenantId));
      violations.addAll(orderCancelledActiveReservation(tenantId));
      violations.addAll(orderTerminalActiveReservation(tenantId));
      violations.addAll(orderReadyToPickHold(tenantId));
      violations.addAll(orderReadyToPickCoverage(tenantId));
    }
    return violations;
  }

  private List<Violation> stockReservedBounds(UUID tenantId) {
    List<UUID> ids =
        jdbc.query(
            """
            SELECT id FROM inventory
            WHERE reserved < 0 OR reserved > on_hand
            LIMIT ?
            """,
            (rs, row) -> rs.getObject("id", UUID.class),
            ENTITY_LIMIT);
    return ids.isEmpty()
        ? List.of()
        : List.of(Violation.of(InvariantCodes.STOCK_RESERVED_BOUNDS, tenantId, ids));
  }

  private List<Violation> stockActiveReservationMismatch(UUID tenantId) {
    List<UUID> ids =
        jdbc.query(
            """
            SELECT i.id
            FROM inventory AS i
            LEFT JOIN (
              SELECT sku_id, warehouse_id, sum(qty) AS qty
              FROM stock_reservation
              WHERE status = 'ACTIVE'
              GROUP BY sku_id, warehouse_id
            ) AS r USING (sku_id, warehouse_id)
            WHERE coalesce(r.qty, 0) <> i.reserved
            LIMIT ?
            """,
            (rs, row) -> rs.getObject("id", UUID.class),
            ENTITY_LIMIT);
    return ids.isEmpty()
        ? List.of()
        : List.of(Violation.of(InvariantCodes.STOCK_ACTIVE_RESERVATION_MISMATCH, tenantId, ids));
  }

  private List<Violation> stockLedgerMismatch(UUID tenantId) {
    List<UUID> ids =
        jdbc.query(
            """
            SELECT i.id
            FROM inventory AS i
            LEFT JOIN (
              SELECT sku_id, warehouse_id, sum(delta_on_hand) AS on_hand,
                     sum(delta_reserved) AS reserved
              FROM inventory_ledger
              GROUP BY sku_id, warehouse_id
            ) AS l USING (sku_id, warehouse_id)
            WHERE coalesce(l.on_hand, 0) <> i.on_hand
               OR coalesce(l.reserved, 0) <> i.reserved
            LIMIT ?
            """,
            (rs, row) -> rs.getObject("id", UUID.class),
            ENTITY_LIMIT);
    return ids.isEmpty()
        ? List.of()
        : List.of(Violation.of(InvariantCodes.STOCK_LEDGER_MISMATCH, tenantId, ids));
  }

  private List<Violation> stockReservationOwnerSplit(UUID tenantId) {
    List<UUID> ids =
        jdbc.query(
            """
            SELECT reservation_group_id AS id
            FROM stock_reservation
            WHERE status = 'ACTIVE'
            GROUP BY reservation_group_id
            HAVING count(DISTINCT (owner_type, owner_ref)) > 1
            LIMIT ?
            """,
            (rs, row) -> rs.getObject("id", UUID.class),
            ENTITY_LIMIT);
    return ids.isEmpty()
        ? List.of()
        : List.of(Violation.of(InvariantCodes.STOCK_RESERVATION_OWNER_SPLIT, tenantId, ids));
  }

  private void logMalformedOrderOwnerRefs(UUID tenantId) {
    List<String> refs =
        jdbc.query(
            """
            SELECT sr.id::text || ':' || sr.owner_ref AS ref
            FROM stock_reservation sr
            WHERE sr.status = 'ACTIVE'
              AND sr.owner_type = 'ORDER'
              AND btrim(sr.owner_ref) <> ''
              AND lower(sr.owner_ref) !~ ?
              AND sr.owner_ref !~ '^ord-'
            LIMIT ?
            """,
            (rs, row) -> rs.getString("ref"),
            ORDER_OWNER_UUID_PATTERN,
            ENTITY_LIMIT);
    for (String ref : refs) {
      log.warn(
          "invariant skipped orphan check for malformed ORDER owner_ref tenant_id={} {}",
          tenantId,
          ref);
    }
  }

  private List<Violation> orderReservationOrphan(UUID tenantId) {
    List<UUID> ids =
        jdbc.query(
            """
            SELECT sr.id
            FROM stock_reservation sr
            WHERE sr.status = 'ACTIVE'
              AND sr.owner_type = 'ORDER'
              AND lower(sr.owner_ref) ~ ?
              AND NOT EXISTS (
                SELECT 1 FROM sales_order o
                WHERE o.tenant_id = sr.tenant_id AND o.id::text = lower(sr.owner_ref)
              )
            LIMIT ?
            """,
            (rs, row) -> rs.getObject("id", UUID.class),
            ORDER_OWNER_UUID_PATTERN,
            ENTITY_LIMIT);
    return ids.isEmpty()
        ? List.of()
        : List.of(Violation.of(InvariantCodes.ORDER_RESERVATION_ORPHAN, tenantId, ids));
  }

  private List<Violation> orderCancelledActiveReservation(UUID tenantId) {
    List<UUID> ids =
        jdbc.query(
            """
            SELECT sr.id
            FROM stock_reservation sr
            JOIN sales_order o
              ON o.tenant_id = sr.tenant_id AND o.id::text = sr.owner_ref
            WHERE sr.status = 'ACTIVE'
              AND sr.owner_type = 'ORDER'
              AND o.order_status = 'CANCELLED'
            LIMIT ?
            """,
            (rs, row) -> rs.getObject("id", UUID.class),
            ENTITY_LIMIT);
    return ids.isEmpty()
        ? List.of()
        : List.of(Violation.of(InvariantCodes.ORDER_CANCELLED_ACTIVE_RESERVATION, tenantId, ids));
  }

  /**
   * COMPLETED orders must not keep ACTIVE ORDER reservations (release window is synchronous on
   * transition).
   */
  private List<Violation> orderTerminalActiveReservation(UUID tenantId) {
    List<UUID> ids =
        jdbc.query(
            """
            SELECT sr.id
            FROM stock_reservation sr
            JOIN sales_order o
              ON o.tenant_id = sr.tenant_id AND o.id::text = sr.owner_ref
            WHERE sr.status = 'ACTIVE'
              AND sr.owner_type = 'ORDER'
              AND o.order_status = 'COMPLETED'
            LIMIT ?
            """,
            (rs, row) -> rs.getObject("id", UUID.class),
            ENTITY_LIMIT);
    return ids.isEmpty()
        ? List.of()
        : List.of(Violation.of(InvariantCodes.ORDER_TERMINAL_ACTIVE_RESERVATION, tenantId, ids));
  }

  private List<Violation> orderReadyToPickHold(UUID tenantId) {
    List<UUID> ids =
        jdbc.query(
            """
            SELECT id FROM sales_order
            WHERE fulfillment_status = 'READY_TO_PICK'
              AND order_status = 'ACTIVE'
              AND hold_reason NOT IN ('NONE', 'CHANNEL_CANCEL_PENDING')
            LIMIT ?
            """,
            (rs, row) -> rs.getObject("id", UUID.class),
            ENTITY_LIMIT);
    return ids.isEmpty()
        ? List.of()
        : List.of(Violation.of(InvariantCodes.ORDER_READY_TO_PICK_HOLD, tenantId, ids));
  }

  private List<Violation> orderReadyToPickCoverage(UUID tenantId) {
    Instant now = clock.instant();
    List<UUID> orderIds =
        jdbc.query(
            """
            SELECT o.id
            FROM sales_order o
            JOIN channel_account ca ON ca.id = o.channel_account_id AND ca.tenant_id = o.tenant_id
            WHERE o.fulfillment_status = 'READY_TO_PICK'
              AND o.order_status = 'ACTIVE'
              AND o.hold_reason = 'NONE'
              AND ca.mode = 'ACTIVE'
              AND ca.status = 'CONNECTED'
            """,
            (rs, row) -> rs.getObject("id", UUID.class));
    List<UUID> bad = new ArrayList<>();
    for (UUID orderId : orderIds) {
      List<ReserveItem> needs = mappedLineNeeds(orderId);
      if (!needs.isEmpty() && !coverage.covers(orderId, needs, now)) {
        bad.add(orderId);
      }
    }
    return bad.isEmpty()
        ? List.of()
        : List.of(
            Violation.of(InvariantCodes.ORDER_READY_TO_PICK_COVERAGE, tenantId, capEntityIds(bad)));
  }

  private static List<UUID> capEntityIds(List<UUID> ids) {
    return ids.size() <= ENTITY_LIMIT ? ids : List.copyOf(ids.subList(0, ENTITY_LIMIT));
  }

  private List<ReserveItem> mappedLineNeeds(UUID orderId) {
    return jdbc.query(
        """
        SELECT ol.sku_id, ol.qty, w.id AS warehouse_id
        FROM order_line ol
        JOIN warehouse w ON w.tenant_id = ol.tenant_id AND w.is_default = true
        WHERE ol.order_id = ? AND ol.sku_id IS NOT NULL
        """,
        (rs, row) ->
            new ReserveItem(
                rs.getObject("sku_id", UUID.class),
                rs.getInt("qty"),
                rs.getObject("warehouse_id", UUID.class)),
        orderId);
  }

  /** Formats violations for test failures (ids only). */
  public static String formatFailures(List<Violation> violations) {
    StringBuilder sb = new StringBuilder();
    for (Violation v : violations) {
      sb.append(v.code());
      if (v.tenantId() != null) {
        sb.append(" tenant=").append(v.tenantId());
      }
      if (!v.entityIds().isEmpty()) {
        sb.append(" ids=").append(v.entityIds());
      }
      sb.append('\n');
    }
    return sb.toString().trim();
  }
}
