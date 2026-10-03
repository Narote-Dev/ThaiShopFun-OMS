package com.thaishopfun.oms.stock;

import com.thaishopfun.oms.auth.UuidV7;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
  static final String REF_DOCUMENT_LINE = "stock_document_line";
  static final String REF_RETURN_LINE = "return_line";

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

  /**
   * Re-reads on_hand and reserved for rows this transaction already locked. Used after an inline
   * settle so shortfall checks see the released reserved qty without a second lock pass.
   */
  void reloadLockedInventory(Map<SkuWarehouse, InventoryRow> locked) {
    for (SkuWarehouse key : locked.keySet()) {
      InventoryRow row =
          jdbc.queryForObject(
              """
              SELECT id, sku_id, warehouse_id, on_hand, reserved
              FROM inventory
              WHERE sku_id = ? AND warehouse_id = ?
              """,
              (rs, rowNum) -> inventoryRow(rs),
              key.skuId(),
              key.warehouseId());
      locked.put(key, row);
    }
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

  // Change: T08A on_hand writes for stock documents, voids, and return restocks.
  /**
   * {@code on_hand += delta} per row, only where the result stays at or above {@code reserved} (and
   * so at or above 0). Stock documents, voids, and return restocks. Returns the rows updated.
   */
  int adjustOnHand(Map<UUID, Integer> deltaByInventoryId) {
    return applyInventory(
        """
        UPDATE inventory AS i
        SET on_hand = i.on_hand + d.qty,
            stock_version = i.stock_version + 1,
            updated_at = now()
        FROM unnest(?::uuid[], ?::int[]) AS d (id, qty)
        WHERE i.id = d.id
          AND i.on_hand + d.qty >= i.reserved
          AND i.on_hand + d.qty >= 0
        """,
        deltaByInventoryId);
  }

  /**
   * Creates the missing {@code (sku, warehouse)} rows with {@code on_hand = reserved = 0}, in (sku,
   * warehouse) order so two creators of overlapping sets cannot deadlock. Meant for its own short
   * transaction before a post: the post then only locks rows that already exist.
   */
  void ensureInventory(UUID tenantId, Collection<SkuWarehouse> keys) {
    if (keys.isEmpty()) {
      return;
    }
    List<SkuWarehouse> sorted = new ArrayList<>(new LinkedHashSet<>(keys));
    sorted.sort(
        Comparator.comparing((SkuWarehouse key) -> key.skuId().toString())
            .thenComparing(key -> key.warehouseId().toString()));
    List<UUID> ids = new ArrayList<>();
    List<UUID> skus = new ArrayList<>();
    List<UUID> warehouses = new ArrayList<>();
    for (SkuWarehouse key : sorted) {
      ids.add(UuidV7.generate());
      skus.add(key.skuId());
      warehouses.add(key.warehouseId());
    }
    jdbc.update(
        """
        INSERT INTO inventory (id, tenant_id, sku_id, warehouse_id, on_hand, reserved)
        SELECT k.id, ?, k.sku_id, k.warehouse_id, 0, 0
        FROM unnest(?::uuid[], ?::uuid[], ?::uuid[]) WITH ORDINALITY
          AS k (id, sku_id, warehouse_id, n)
        ORDER BY k.n
        ON CONFLICT (tenant_id, sku_id, warehouse_id) DO NOTHING
        """,
        ps -> {
          ps.setObject(1, tenantId);
          uuidArray(ps, 2, ids);
          uuidArray(ps, 3, skus);
          uuidArray(ps, 4, warehouses);
        });
  }

  /** The (sku, warehouse) pairs among {@code keys} that already have any ledger entry. */
  Set<SkuWarehouse> withLedger(Collection<SkuWarehouse> keys) {
    Set<SkuWarehouse> found = new HashSet<>();
    if (keys.isEmpty()) {
      return found;
    }
    List<UUID> skus = new ArrayList<>();
    List<UUID> warehouses = new ArrayList<>();
    for (SkuWarehouse key : keys) {
      skus.add(key.skuId());
      warehouses.add(key.warehouseId());
    }
    jdbc.query(
        """
        SELECT k.sku_id, k.warehouse_id
        FROM unnest(?::uuid[], ?::uuid[]) AS k (sku_id, warehouse_id)
        WHERE EXISTS (
          SELECT 1 FROM inventory_ledger AS l
          WHERE l.sku_id = k.sku_id AND l.warehouse_id = k.warehouse_id
        )
        """,
        ps -> {
          uuidArray(ps, 1, skus);
          uuidArray(ps, 2, warehouses);
        },
        (ResultSet rs) -> {
          found.add(
              new SkuWarehouse(
                  rs.getObject("sku_id", UUID.class), rs.getObject("warehouse_id", UUID.class)));
        });
    return found;
  }

  // Change: T08A ref_type parameter; reservation callers keep the stock_reservation default.
  /** Reservation entries: {@code ref_type = stock_reservation}. */
  void insertLedger(UUID tenantId, String reason, String actor, Collection<LedgerEntry> entries) {
    insertLedger(tenantId, reason, REF_TYPE, actor, entries);
  }

  /**
   * {@code refType} names what {@link LedgerEntry#refId()} points at: {@value #REF_TYPE}, {@value
   * #REF_DOCUMENT_LINE}, or {@value #REF_RETURN_LINE}.
   */
  void insertLedger(
      UUID tenantId, String reason, String refType, String actor, Collection<LedgerEntry> entries) {
    if (entries.isEmpty()) {
      return;
    }
    // Step 1: Assign ledger_seq while the caller still holds each inventory row lock.
    Map<UUID, Long> seqByEntryId = new LinkedHashMap<>();
    LinkedHashMap<SkuWarehouse, List<LedgerEntry>> grouped = new LinkedHashMap<>();
    for (LedgerEntry entry : entries) {
      grouped
          .computeIfAbsent(
              new SkuWarehouse(entry.skuId(), entry.warehouseId()), k -> new ArrayList<>())
          .add(entry);
    }
    for (Map.Entry<SkuWarehouse, List<LedgerEntry>> group : grouped.entrySet()) {
      SkuWarehouse key = group.getKey();
      List<LedgerEntry> lines = group.getValue();
      int count = lines.size();
      Long endSeq =
          jdbc.queryForObject(
              """
              UPDATE inventory
              SET ledger_seq = ledger_seq + ?
              WHERE tenant_id = ? AND sku_id = ? AND warehouse_id = ?
              RETURNING ledger_seq
              """,
              Long.class,
              count,
              tenantId,
              key.skuId(),
              key.warehouseId());
      long startSeq = endSeq - count + 1;
      for (int i = 0; i < count; i++) {
        seqByEntryId.put(lines.get(i).id(), startSeq + i);
      }
    }
    // Step 2: Append ledger rows with their commit-order sequence.
    List<UUID> ids = new ArrayList<>();
    List<UUID> skus = new ArrayList<>();
    List<UUID> warehouses = new ArrayList<>();
    List<Integer> onHand = new ArrayList<>();
    List<Integer> reserved = new ArrayList<>();
    List<UUID> refs = new ArrayList<>();
    List<Long> seqs = new ArrayList<>();
    for (LedgerEntry entry : entries) {
      ids.add(entry.id());
      skus.add(entry.skuId());
      warehouses.add(entry.warehouseId());
      onHand.add(entry.deltaOnHand());
      reserved.add(entry.deltaReserved());
      refs.add(entry.refId());
      seqs.add(seqByEntryId.get(entry.id()));
    }
    jdbc.update(
        """
        INSERT INTO inventory_ledger
          (id, tenant_id, sku_id, warehouse_id, delta_on_hand, delta_reserved, reason,
           ref_type, ref_id, actor, ledger_seq)
        SELECT l.id, ?, l.sku_id, l.warehouse_id, l.d_on_hand, l.d_reserved, ?, ?, l.ref_id, ?,
               l.ledger_seq
        FROM unnest(?::uuid[], ?::uuid[], ?::uuid[], ?::int[], ?::int[], ?::uuid[], ?::int8[])
          AS l (id, sku_id, warehouse_id, d_on_hand, d_reserved, ref_id, ledger_seq)
        """,
        ps -> {
          ps.setObject(1, tenantId);
          ps.setString(2, reason);
          ps.setString(3, refType);
          ps.setString(4, actor);
          uuidArray(ps, 5, ids);
          uuidArray(ps, 6, skus);
          uuidArray(ps, 7, warehouses);
          intArray(ps, 8, onHand);
          intArray(ps, 9, reserved);
          uuidArray(ps, 10, refs);
          longArray(ps, 11, seqs);
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

  /** This owner's ACTIVE CHECKOUT rows with {@code expires_at <= now} (unswept expiry). */
  List<ReservationRow> expiredActiveCheckoutByOwner(StockOwner owner, Instant now) {
    return jdbc.query(
        "SELECT "
            + RESERVATION_COLUMNS
            + " FROM stock_reservation "
            + "WHERE owner_type = ? AND owner_ref = ? AND status = 'ACTIVE' "
            + "AND expires_at IS NOT NULL AND expires_at <= ? ORDER BY id",
        (rs, row) -> reservationRow(rs),
        owner.type().name(),
        owner.ref(),
        OffsetDateTime.ofInstant(now, ZoneOffset.UTC));
  }

  /**
   * Whether the owner still has an ACTIVE row that has not passed {@code expires_at}. ORDER rows
   * ({@code expires_at} null) always count.
   */
  boolean ownerHasActiveUnexpired(StockOwner owner, Instant now) {
    Boolean exists =
        jdbc.queryForObject(
            """
            SELECT EXISTS (
              SELECT 1 FROM stock_reservation
              WHERE owner_type = ? AND owner_ref = ? AND status = 'ACTIVE'
                AND (expires_at IS NULL OR expires_at > ?)
            )
            """,
            Boolean.class,
            owner.type().name(),
            owner.ref(),
            OffsetDateTime.ofInstant(now, ZoneOffset.UTC));
    return Boolean.TRUE.equals(exists);
  }

  /**
   * Other owners' ACTIVE CHECKOUT rows past {@code now} that touch one of {@code skuIds}. Used to
   * inline-expire stale holds on the reserve path (T12A P2).
   */
  List<ReservationRow> expiredActiveCheckoutForSkus(
      StockOwner excludeOwner, Collection<UUID> skuIds, Instant now, int limit) {
    if (skuIds.isEmpty() || limit <= 0) {
      return List.of();
    }
    return jdbc.query(
        "SELECT "
            + RESERVATION_COLUMNS
            + " FROM stock_reservation "
            + "WHERE status = 'ACTIVE' AND owner_type = 'CHECKOUT' AND expires_at <= ? "
            + "AND sku_id = ANY (?) AND NOT (owner_type = ? AND owner_ref = ?) "
            + "ORDER BY expires_at, id LIMIT ?",
        ps -> {
          ps.setObject(1, OffsetDateTime.ofInstant(now, ZoneOffset.UTC));
          uuidArray(ps, 2, skuIds);
          ps.setString(3, excludeOwner.type().name());
          ps.setString(4, excludeOwner.ref());
          ps.setInt(5, limit);
        },
        (rs, row) -> reservationRow(rs));
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
    return transferOwnerWithExpiry(ids, target, null);
  }

  int transferOwnerWithExpiry(Collection<UUID> ids, StockOwner target, Instant expiresAt) {
    return jdbc.update(
        """
        UPDATE stock_reservation
        SET owner_type = ?, owner_ref = ?, expires_at = ?, updated_at = now()
        WHERE id = ANY (?) AND status = 'ACTIVE'
        """,
        ps -> {
          ps.setString(1, target.type().name());
          ps.setString(2, target.ref());
          ps.setObject(
              3, expiresAt == null ? null : OffsetDateTime.ofInstant(expiresAt, ZoneOffset.UTC));
          uuidArray(ps, 4, ids);
        });
  }

  int clearActiveExpiry(StockOwner owner) {
    return jdbc.update(
        """
        UPDATE stock_reservation
        SET expires_at = NULL, updated_at = now()
        WHERE owner_type = ? AND owner_ref = ? AND status = 'ACTIVE'
        """,
        owner.type().name(),
        owner.ref());
  }

  /** Adds quantity to an ACTIVE reservation row (same owner+sku). */
  int increaseActiveReservationQty(UUID reservationId, int additionalQty) {
    return jdbc.update(
        """
        UPDATE stock_reservation
        SET qty = qty + ?, updated_at = now()
        WHERE id = ? AND status = 'ACTIVE'
        """,
        additionalQty,
        reservationId);
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

  private static void longArray(PreparedStatement ps, int index, Collection<Long> values)
      throws SQLException {
    ps.setArray(index, ps.getConnection().createArrayOf("int8", values.toArray(new Long[0])));
  }
}
