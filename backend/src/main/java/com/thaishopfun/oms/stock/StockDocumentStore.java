package com.thaishopfun.oms.stock;

import com.thaishopfun.oms.stock.StockRepository.SkuWarehouse;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The engine's view of {@code stock_document} and its lines during post and void. The document CRUD
 * lives in {@code oms.stockdoc}; only the status flip, the COUNT {@code qty} write-back, and the
 * reads under the document lock are here. RLS scopes every query to the transaction's tenant.
 */
@Repository
class StockDocumentStore {

  record DocumentRow(
      UUID id,
      StockDocumentType type,
      String status,
      String note,
      Instant countStartedAt,
      Instant postedAt,
      UUID postedBy) {}

  record LineRow(
      UUID id,
      UUID skuId,
      UUID warehouseId,
      int qty,
      Integer systemQtyAtStart,
      Integer countedQty,
      String reasonCode) {

    SkuWarehouse key() {
      return new SkuWarehouse(skuId, warehouseId);
    }
  }

  record PostedEntry(UUID lineId, UUID skuId, UUID warehouseId, String reason, int deltaOnHand) {

    SkuWarehouse key() {
      return new SkuWarehouse(skuId, warehouseId);
    }
  }

  private static final String DOCUMENT_COLUMNS =
      "id, type, status, note, count_started_at, posted_at, posted_by";

  private final JdbcTemplate jdbc;

  StockDocumentStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** Unlocked. Used before the post transaction and for the status of a replayed post. */
  DocumentRow find(UUID documentId) {
    List<DocumentRow> rows =
        jdbc.query(
            "SELECT " + DOCUMENT_COLUMNS + " FROM stock_document WHERE id = ?",
            (rs, n) -> document(rs),
            documentId);
    return rows.isEmpty() ? null : rows.get(0);
  }

  /** Second lock of a post or void, right after the idempotency key. */
  DocumentRow lock(UUID documentId) {
    List<DocumentRow> rows =
        jdbc.query(
            "SELECT " + DOCUMENT_COLUMNS + " FROM stock_document WHERE id = ? FOR UPDATE",
            (rs, n) -> document(rs),
            documentId);
    return rows.isEmpty() ? null : rows.get(0);
  }

  List<LineRow> lines(UUID documentId) {
    return jdbc.query(
        """
        SELECT id, sku_id, warehouse_id, qty, system_qty_at_start, counted_qty, reason_code
        FROM stock_document_line
        WHERE document_id = ?
        ORDER BY id
        """,
        (rs, n) ->
            new LineRow(
                rs.getObject("id", UUID.class),
                rs.getObject("sku_id", UUID.class),
                rs.getObject("warehouse_id", UUID.class),
                rs.getInt("qty"),
                rs.getObject("system_qty_at_start", Integer.class),
                rs.getObject("counted_qty", Integer.class),
                rs.getString("reason_code")),
        documentId);
  }

  /** Unlocked distinct (sku, warehouse) pairs, for creating missing inventory rows first. */
  Set<SkuWarehouse> lineKeys(UUID documentId) {
    Set<SkuWarehouse> keys = new LinkedHashSet<>();
    jdbc.query(
        """
        SELECT DISTINCT sku_id, warehouse_id FROM stock_document_line
        WHERE document_id = ?
        ORDER BY sku_id, warehouse_id
        """,
        (ResultSet rs) -> {
          keys.add(
              new SkuWarehouse(
                  rs.getObject("sku_id", UUID.class), rs.getObject("warehouse_id", UUID.class)));
        },
        documentId);
    return keys;
  }

  /** COUNT: the applied correction goes into {@code qty} while the header is still DRAFT. */
  void setLineQty(Map<UUID, Integer> qtyByLineId) {
    if (qtyByLineId.isEmpty()) {
      return;
    }
    List<UUID> ids = new ArrayList<>(qtyByLineId.keySet());
    List<Integer> qtys = ids.stream().map(qtyByLineId::get).toList();
    jdbc.update(
        """
        UPDATE stock_document_line AS l
        SET qty = d.qty, updated_at = now()
        FROM unnest(?::uuid[], ?::int[]) AS d (id, qty)
        WHERE l.id = d.id
        """,
        ps -> {
          uuidArray(ps, 1, ids);
          ps.setArray(2, ps.getConnection().createArrayOf("int4", qtys.toArray(new Integer[0])));
        });
  }

  int markPosted(UUID documentId, Instant postedAt, UUID postedBy) {
    return jdbc.update(
        """
        UPDATE stock_document
        SET status = 'POSTED', posted_at = ?, posted_by = ?, updated_at = now()
        WHERE id = ? AND status = 'DRAFT'
        """,
        OffsetDateTime.ofInstant(postedAt, ZoneOffset.UTC),
        postedBy,
        documentId);
  }

  int markVoid(UUID documentId) {
    return jdbc.update(
        """
        UPDATE stock_document SET status = 'VOID', updated_at = now()
        WHERE id = ? AND status = 'POSTED'
        """,
        documentId);
  }

  /** The ledger rows a post wrote for these lines, oldest first. */
  List<PostedEntry> postedEntries(Collection<UUID> lineIds) {
    if (lineIds.isEmpty()) {
      return List.of();
    }
    return jdbc.query(
        """
        SELECT ref_id, sku_id, warehouse_id, reason, delta_on_hand
        FROM inventory_ledger
        WHERE ref_type = ? AND ref_id = ANY (?)
        ORDER BY created_at, id
        """,
        ps -> {
          ps.setString(1, StockRepository.REF_DOCUMENT_LINE);
          uuidArray(ps, 2, lineIds);
        },
        (rs, n) ->
            new PostedEntry(
                rs.getObject("ref_id", UUID.class),
                rs.getObject("sku_id", UUID.class),
                rs.getObject("warehouse_id", UUID.class),
                rs.getString("reason"),
                rs.getInt("delta_on_hand")));
  }

  private static DocumentRow document(ResultSet rs) throws SQLException {
    OffsetDateTime started = rs.getObject("count_started_at", OffsetDateTime.class);
    OffsetDateTime posted = rs.getObject("posted_at", OffsetDateTime.class);
    return new DocumentRow(
        rs.getObject("id", UUID.class),
        StockDocumentType.valueOf(rs.getString("type")),
        rs.getString("status"),
        rs.getString("note"),
        started == null ? null : started.toInstant(),
        posted == null ? null : posted.toInstant(),
        rs.getObject("posted_by", UUID.class));
  }

  private static void uuidArray(PreparedStatement ps, int index, Collection<UUID> values)
      throws SQLException {
    ps.setArray(index, ps.getConnection().createArrayOf("uuid", values.toArray(new UUID[0])));
  }
}
