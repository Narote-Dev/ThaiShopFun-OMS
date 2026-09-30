package com.thaishopfun.oms.stock;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Plain JDBC over the V4 catalog and stock tables. Read-only on {@code sku}, {@code
 * sku_bundle_component}, {@code warehouse}, and {@code channel_listing} (owned by T07). Every query
 * runs under the transaction's tenant, so RLS scopes it without a tenant filter.
 */
@Repository
class StockRepository {

  static final String REF_TYPE = "stock_reservation";

  record SkuWarehouse(UUID skuId, UUID warehouseId) {}

  record Component(UUID skuId, int qty) {}

  record SkuInfo(UUID id, boolean bundle, List<Component> components) {}

  record InventoryRow(UUID id, UUID skuId, UUID warehouseId, int onHand, int reserved) {

    int available() {
      return onHand - reserved;
    }

    SkuWarehouse key() {
      return new SkuWarehouse(skuId, warehouseId);
    }
  }

  record ReservationRow(
      UUID id,
      UUID groupId,
      OwnerType ownerType,
      String ownerRef,
      UUID skuId,
      UUID warehouseId,
      int qty,
      String status,
      Instant expiresAt) {

    boolean active() {
      return "ACTIVE".equals(status);
    }

    boolean ownedBy(StockOwner owner) {
      return ownerType == owner.type() && ownerRef.equals(owner.ref());
    }

    boolean expiredAt(Instant now) {
      return expiresAt != null && !expiresAt.isAfter(now);
    }

    SkuWarehouse key() {
      return new SkuWarehouse(skuId, warehouseId);
    }

    ReservedLine line() {
      return new ReservedLine(id, skuId, warehouseId, qty);
    }
  }

  record ListingRow(UUID skuId, int safetyBuffer) {}

  record LedgerEntry(
      UUID id, UUID skuId, UUID warehouseId, int deltaOnHand, int deltaReserved, UUID refId) {}

  private static final String RESERVATION_COLUMNS =
      "id, reservation_group_id, owner_type, owner_ref, sku_id, warehouse_id, qty, status, "
          + "expires_at";

  private final JdbcTemplate jdbc;

  StockRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  // ---- owner -------------------------------------------------------------------------------

  /**
   * Serializes every writer that can create ACTIVE rows for one owner (reserve, transfer target).
   * Transaction-scoped. Taken after the idempotency key and before any inventory row lock.
   */
  void lockOwner(UUID tenantId, StockOwner owner) {
    String key = "stock.owner\u001f" + tenantId + "\u001f" + owner.type() + "\u001f" + owner.ref();
    jdbc.query(
        "SELECT pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended(?, 8))",
        rs -> {
          rs.next();
          return null;
        },
        key);
  }

  boolean ownerHasActive(StockOwner owner) {
    Boolean exists =
        jdbc.queryForObject(
            """
            SELECT EXISTS (
              SELECT 1 FROM stock_reservation
              WHERE owner_type = ? AND owner_ref = ? AND status = 'ACTIVE'
            )
            """,
            Boolean.class,
            owner.type().name(),
            owner.ref());
    return Boolean.TRUE.equals(exists);
  }

  // ---- catalog (read-only) -----------------------------------------------------------------

  UUID defaultWarehouse() {
    List<UUID> ids =
        jdbc.queryForList("SELECT id FROM warehouse WHERE is_default LIMIT 1", UUID.class);
    return ids.isEmpty() ? null : ids.get(0);
  }

  /** Visible SKUs with their components. Unknown or other-tenant ids are simply absent. */
  Map<UUID, SkuInfo> skus(Collection<UUID> skuIds) {
    Map<UUID, Boolean> bundles = new LinkedHashMap<>();
    Map<UUID, List<Component>> components = new LinkedHashMap<>();
    jdbc.query(
        """
        SELECT s.id, s.is_bundle, c.component_sku_id, c.qty
        FROM sku AS s
        LEFT JOIN sku_bundle_component AS c ON c.bundle_sku_id = s.id AND s.is_bundle
        WHERE s.id = ANY (?)
        ORDER BY s.id, c.component_sku_id
        """,
        ps -> uuidArray(ps, 1, skuIds),
        (ResultSet rs) -> {
          UUID id = rs.getObject("id", UUID.class);
          bundles.put(id, rs.getBoolean("is_bundle"));
          List<Component> list = components.computeIfAbsent(id, ignored -> new ArrayList<>());
          UUID component = rs.getObject("component_sku_id", UUID.class);
          if (component != null) {
            list.add(new Component(component, rs.getInt("qty")));
          }
        });
    Map<UUID, SkuInfo> result = new LinkedHashMap<>();
    for (Map.Entry<UUID, Boolean> entry : bundles.entrySet()) {
      result.put(
          entry.getKey(),
          new SkuInfo(
              entry.getKey(), entry.getValue(), List.copyOf(components.get(entry.getKey()))));
    }
    return result;
  }

  /** Every bundle that has one of these SKUs as a component (index on component_sku_id). */
  Set<UUID> bundlesUsing(Collection<UUID> componentSkuIds) {
    if (componentSkuIds.isEmpty()) {
      return Set.of();
    }
    List<UUID> ids =
        jdbc.query(
            """
            SELECT DISTINCT bundle_sku_id
            FROM sku_bundle_component
            WHERE component_sku_id = ANY (?)
            """,
            ps -> uuidArray(ps, 1, componentSkuIds),
            (rs, row) -> rs.getObject(1, UUID.class));
    return new HashSet<>(ids);
  }

  /** A visible listing, or null. {@code skuId} is null while the listing is unmapped. */
  ListingRow listing(UUID listingId) {
    List<ListingRow> rows =
        jdbc.query(
            "SELECT sku_id, safety_buffer FROM channel_listing WHERE id = ?",
            (rs, row) ->
                new ListingRow(rs.getObject("sku_id", UUID.class), rs.getInt("safety_buffer")),
            listingId);
    return rows.isEmpty() ? null : rows.get(0);
  }

  // ---- inventory ---------------------------------------------------------------------------

  /**
   * Locks the inventory rows for these (sku, warehouse) pairs in ascending {@code inventory.id}.
   * The sort runs below the row lock, so rows are locked in id order. This is the first row lock of
   * every engine write, which is what keeps concurrent writers from deadlocking.
   */
  Map<SkuWarehouse, InventoryRow> lockInventory(Collection<SkuWarehouse> keys) {
    Map<SkuWarehouse, InventoryRow> rows = new LinkedHashMap<>();
    if (keys.isEmpty()) {
      return rows;
    }
    List<UUID> skus = new ArrayList<>();
    List<UUID> warehouses = new ArrayList<>();
    for (SkuWarehouse key : keys) {
      skus.add(key.skuId());
      warehouses.add(key.warehouseId());
    }
    jdbc.query(
        """
        SELECT i.id, i.sku_id, i.warehouse_id, i.on_hand, i.reserved
        FROM inventory AS i
        WHERE (i.sku_id, i.warehouse_id) IN (
          SELECT k.sku_id, k.warehouse_id FROM unnest(?::uuid[], ?::uuid[]) AS k (sku_id, warehouse_id)
        )
        ORDER BY i.id
        FOR UPDATE OF i
        """,
        ps -> {
          uuidArray(ps, 1, skus);
          uuidArray(ps, 2, warehouses);
        },
        (ResultSet rs) -> {
          InventoryRow row = inventoryRow(rs);
          rows.put(row.key(), row);
        });
    return rows;
  }

  /** Unlocked read for availability. */
  Map<UUID, InventoryRow> inventoryInWarehouse(UUID warehouseId, Collection<UUID> skuIds) {
    Map<UUID, InventoryRow> rows = new LinkedHashMap<>();
    if (skuIds.isEmpty()) {
      return rows;
    }
    jdbc.query(
        """
        SELECT id, sku_id, warehouse_id, on_hand, reserved
        FROM inventory
        WHERE warehouse_id = ? AND sku_id = ANY (?)
        """,
        ps -> {
          ps.setObject(1, warehouseId);
          uuidArray(ps, 2, skuIds);
        },
        (ResultSet rs) -> {
          InventoryRow row = inventoryRow(rs);
          rows.put(row.skuId(), row);
        });
    return rows;
  }

  /** {@code reserved += qty} where there is room. Returns the rows updated. */
  int reserveInventory(Map<UUID, Integer> qtyByInventoryId) {
    return applyInventory(
        """
        UPDATE inventory AS i
        SET reserved = i.reserved + d.qty,
            stock_version = i.stock_version + 1,
            updated_at = now()
        FROM unnest(?::uuid[], ?::int[]) AS d (id, qty)
        WHERE i.id = d.id
          AND i.on_hand - i.reserved >= d.qty
        """,
        qtyByInventoryId);
  }

  /** {@code reserved -= qty}. Release, unpack, and expiry. */
  int releaseInventory(Map<UUID, Integer> qtyByInventoryId) {
    return applyInventory(
        """
        UPDATE inventory AS i
        SET reserved = i.reserved - d.qty,
            stock_version = i.stock_version + 1,
            updated_at = now()
        FROM unnest(?::uuid[], ?::int[]) AS d (id, qty)
        WHERE i.id = d.id
          AND i.reserved >= d.qty
        """,
        qtyByInventoryId);
  }

  /** {@code on_hand -= qty} and {@code reserved -= qty} in one conditional UPDATE (ship). */
  int consumeInventory(Map<UUID, Integer> qtyByInventoryId) {
    return applyInventory(
        """
        UPDATE inventory AS i
        SET on_hand = i.on_hand - d.qty,
            reserved = i.reserved - d.qty,
            stock_version = i.stock_version + 1,
            updated_at = now()
        FROM unnest(?::uuid[], ?::int[]) AS d (id, qty)
        WHERE i.id = d.id
          AND i.reserved >= d.qty
          AND i.on_hand >= d.qty
        """,
        qtyByInventoryId);
  }

  private int applyInventory(String sql, Map<UUID, Integer> qtyByInventoryId) {
    List<UUID> ids = new ArrayList<>(qtyByInventoryId.keySet());
    List<Integer> qtys = new ArrayList<>();
    for (UUID id : ids) {
      qtys.add(qtyByInventoryId.get(id));
    }
    return jdbc.update(
        sql,
        ps -> {
          uuidArray(ps, 1, ids);
          intArray(ps, 2, qtys);
        });
  }

  void insertLedger(UUID tenantId, String reason, String actor, Collection<LedgerEntry> entries) {
    if (entries.isEmpty()) {
      return;
    }
    List<UUID> ids = new ArrayList<>();
    List<UUID> skus = new ArrayList<>();
    List<UUID> warehouses = new ArrayList<>();
    List<Integer> onHand = new ArrayList<>();
    List<Integer> reserved = new ArrayList<>();
    List<UUID> refs = new ArrayList<>();
    for (LedgerEntry entry : entries) {
      ids.add(entry.id());
      skus.add(entry.skuId());
      warehouses.add(entry.warehouseId());
      onHand.add(entry.deltaOnHand());
      reserved.add(entry.deltaReserved());
      refs.add(entry.refId());
    }
    jdbc.update(
        """
        INSERT INTO inventory_ledger
          (id, tenant_id, sku_id, warehouse_id, delta_on_hand, delta_reserved, reason,
           ref_type, ref_id, actor)
        SELECT l.id, ?, l.sku_id, l.warehouse_id, l.d_on_hand, l.d_reserved, ?, ?, l.ref_id, ?
        FROM unnest(?::uuid[], ?::uuid[], ?::uuid[], ?::int[], ?::int[], ?::uuid[])
          AS l (id, sku_id, warehouse_id, d_on_hand, d_reserved, ref_id)
        """,
        ps -> {
          ps.setObject(1, tenantId);
          ps.setString(2, reason);
          ps.setString(3, REF_TYPE);
          ps.setString(4, actor);
          uuidArray(ps, 5, ids);
          uuidArray(ps, 6, skus);
          uuidArray(ps, 7, warehouses);
          intArray(ps, 8, onHand);
          intArray(ps, 9, reserved);
          uuidArray(ps, 10, refs);
        });
  }

  // ---- reservations ------------------------------------------------------------------------

  void insertReservations(
      UUID tenantId,
      StockOwner owner,
      UUID groupId,
      Instant expiresAt,
      Collection<ReservedLine> lines) {
    List<UUID> ids = new ArrayList<>();
    List<UUID> skus = new ArrayList<>();
    List<UUID> warehouses = new ArrayList<>();
    List<Integer> qtys = new ArrayList<>();
    for (ReservedLine line : lines) {
      ids.add(line.reservationId());
      skus.add(line.skuId());
      warehouses.add(line.warehouseId());
      qtys.add(line.qty());
    }
    jdbc.update(
        """
        INSERT INTO stock_reservation
          (id, tenant_id, owner_type, owner_ref, sku_id, warehouse_id, qty, status, expires_at,
           reservation_group_id)
        SELECT r.id, ?, ?, ?, r.sku_id, r.warehouse_id, r.qty, 'ACTIVE', ?, ?
        FROM unnest(?::uuid[], ?::uuid[], ?::uuid[], ?::int[]) AS r (id, sku_id, warehouse_id, qty)
        """,
        ps -> {
          ps.setObject(1, tenantId);
          ps.setString(2, owner.type().name());
          ps.setString(3, owner.ref());
          ps.setObject(
              4, expiresAt == null ? null : OffsetDateTime.ofInstant(expiresAt, ZoneOffset.UTC));
          ps.setObject(5, groupId);
          uuidArray(ps, 6, ids);
          uuidArray(ps, 7, skus);
          uuidArray(ps, 8, warehouses);
          intArray(ps, 9, qtys);
        });
  }

  List<ReservationRow> reservationsByGroup(UUID groupId) {
    return jdbc.query(
        "SELECT "
            + RESERVATION_COLUMNS
            + " FROM stock_reservation "
            + "WHERE reservation_group_id = ? ORDER BY id",
        (rs, row) -> reservationRow(rs),
        groupId);
  }

  List<ReservationRow> activeByGroup(UUID groupId) {
    return jdbc.query(
        "SELECT "
            + RESERVATION_COLUMNS
            + " FROM stock_reservation "
            + "WHERE reservation_group_id = ? AND status = 'ACTIVE' ORDER BY id",
        (rs, row) -> reservationRow(rs),
        groupId);
  }

  List<ReservationRow> activeByOwner(StockOwner owner) {
    return jdbc.query(
        "SELECT "
            + RESERVATION_COLUMNS
            + " FROM stock_reservation "
            + "WHERE owner_type = ? AND owner_ref = ? AND status = 'ACTIVE' ORDER BY id",
        (rs, row) -> reservationRow(rs),
        owner.type().name(),
        owner.ref());
  }

  /** Unlocked candidates for the expiry job. Re-checked after the inventory lock. */
  List<ReservationRow> expiredActive(Instant now, int limit) {
    return jdbc.query(
        "SELECT "
            + RESERVATION_COLUMNS
            + " FROM stock_reservation "
            + "WHERE status = 'ACTIVE' AND expires_at <= ? ORDER BY expires_at, id LIMIT ?",
        (rs, row) -> reservationRow(rs),
        OffsetDateTime.ofInstant(now, ZoneOffset.UTC),
        limit);
  }

  /** Second lock step, after inventory. Callers re-check status on what comes back. */
  List<ReservationRow> lockReservations(Collection<UUID> ids) {
    if (ids.isEmpty()) {
      return List.of();
    }
    return jdbc.query(
        "SELECT "
            + RESERVATION_COLUMNS
            + " FROM stock_reservation "
            + "WHERE id = ANY (?) ORDER BY id FOR UPDATE",
        ps -> uuidArray(ps, 1, ids),
        (rs, row) -> reservationRow(rs));
  }

  int setStatus(Collection<UUID> ids, String status) {
    return jdbc.update(
        """
        UPDATE stock_reservation
        SET status = ?, updated_at = now()
        WHERE id = ANY (?) AND status = 'ACTIVE'
        """,
        ps -> {
          ps.setString(1, status);
          uuidArray(ps, 2, ids);
        });
  }

  int transferOwner(Collection<UUID> ids, StockOwner target) {
    return jdbc.update(
        """
        UPDATE stock_reservation
        SET owner_type = ?, owner_ref = ?, expires_at = NULL, updated_at = now()
        WHERE id = ANY (?) AND status = 'ACTIVE'
        """,
        ps -> {
          ps.setString(1, target.type().name());
          ps.setString(2, target.ref());
          uuidArray(ps, 3, ids);
        });
  }

  // ---- mapping -----------------------------------------------------------------------------

  private static InventoryRow inventoryRow(ResultSet rs) throws SQLException {
    return new InventoryRow(
        rs.getObject("id", UUID.class),
        rs.getObject("sku_id", UUID.class),
        rs.getObject("warehouse_id", UUID.class),
        rs.getInt("on_hand"),
        rs.getInt("reserved"));
  }

  private static ReservationRow reservationRow(ResultSet rs) throws SQLException {
    OffsetDateTime expires = rs.getObject("expires_at", OffsetDateTime.class);
    return new ReservationRow(
        rs.getObject("id", UUID.class),
        rs.getObject("reservation_group_id", UUID.class),
        OwnerType.valueOf(rs.getString("owner_type")),
        rs.getString("owner_ref"),
        rs.getObject("sku_id", UUID.class),
        rs.getObject("warehouse_id", UUID.class),
        rs.getInt("qty"),
        rs.getString("status"),
        expires == null ? null : expires.toInstant());
  }

  private static void uuidArray(PreparedStatement ps, int index, Collection<UUID> values)
      throws SQLException {
    ps.setArray(index, ps.getConnection().createArrayOf("uuid", values.toArray(new UUID[0])));
  }

  private static void intArray(PreparedStatement ps, int index, Collection<Integer> values)
      throws SQLException {
    ps.setArray(index, ps.getConnection().createArrayOf("int4", values.toArray(new Integer[0])));
  }
}
