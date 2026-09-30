package com.thaishopfun.oms.stockdoc;

import com.thaishopfun.oms.auth.UuidV7;
import com.thaishopfun.oms.catalog.CatalogAccess;
import com.thaishopfun.oms.catalog.CatalogApiException;
import com.thaishopfun.oms.catalog.CatalogAudit;
import com.thaishopfun.oms.catalog.CatalogTransactions;
import com.thaishopfun.oms.catalog.Fields;
import com.thaishopfun.oms.catalog.PageResult;
import com.thaishopfun.oms.stock.AdjustmentReason;
import com.thaishopfun.oms.stock.StockDocumentType;
import com.thaishopfun.oms.stock.StockMovements;
import com.thaishopfun.oms.stockdoc.StockDocumentView.LineView;
import com.thaishopfun.oms.tenant.TenantContext;
import com.thaishopfun.oms.warehouse.DefaultWarehouse;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Draft stock documents: header and line CRUD and the COUNT start snapshot. Any member may edit a
 * draft (GRACE is read-only through the entitlement gate). Posting and voiding are {@link
 * StockDocumentPosting}. The V4 triggers stay the last line of defence: every line write first
 * locks its document {@code FOR SHARE} (the same lock the line trigger takes), so the DRAFT check
 * here cannot race a post, and a line writer never holds an inventory lock.
 */
@Service
public class StockDocumentService {

  static final int MAX_REFERENCE = 64;
  static final int MAX_NOTE = 1000;

  private static final String SELECT =
      """
      SELECT d.id, d.type, d.status, d.reference_no, d.note, d.count_started_at, d.posted_at,
             d.posted_by, d.created_at, d.updated_at,
             (SELECT count(*) FROM stock_document_line l WHERE l.document_id = d.id) AS line_count,
             (SELECT array_agg(DISTINCT l.warehouse_id) FROM stock_document_line l
              WHERE l.document_id = d.id) AS warehouse_ids
      FROM stock_document d
      """;

  private static final String SELECT_LINES =
      """
      SELECT l.id, l.sku_id, s.sku_code, s.name AS sku_name, l.warehouse_id,
             w.code AS warehouse_code, l.qty, l.system_qty_at_start, l.counted_qty,
             l.reason_code, i.on_hand, i.reserved, l.created_at
      FROM stock_document_line l
      JOIN sku s ON s.id = l.sku_id
      JOIN warehouse w ON w.id = l.warehouse_id
      LEFT JOIN inventory i ON i.sku_id = l.sku_id AND i.warehouse_id = l.warehouse_id
      """;

  private final JdbcTemplate jdbc;
  private final CatalogTransactions tx;
  private final CatalogAudit audit;
  private final DefaultWarehouse defaultWarehouse;
  private final Clock clock;

  public StockDocumentService(
      JdbcTemplate jdbc,
      CatalogTransactions tx,
      CatalogAudit audit,
      DefaultWarehouse defaultWarehouse,
      Clock clock) {
    this.jdbc = jdbc;
    this.tx = tx;
    this.audit = audit;
    this.defaultWarehouse = defaultWarehouse;
    this.clock = clock;
  }

  private record Header(UUID id, StockDocumentType type, String status, Instant countStartedAt) {}

  private record LineInput(
      UUID skuId, UUID warehouseId, int qty, Integer countedQty, String reasonCode) {

    Map<String, Object> audit(UUID documentId) {
      Map<String, Object> values = new LinkedHashMap<>();
      values.put("document_id", documentId.toString());
      values.put("sku_id", skuId.toString());
      values.put("warehouse_id", warehouseId.toString());
      values.put("qty", qty);
      values.put("counted_qty", countedQty);
      values.put("reason_code", reasonCode);
      return values;
    }
  }

  // ---- reads -------------------------------------------------------------------------------

  public PageResult<StockDocumentView> list(
      String type, String status, String from, String to, Integer limit, Integer offset) {
    // Step 1: Validate filters before the query.
    if (type != null && !type.isBlank() && StockDocumentType.parse(type) == null) {
      throw CatalogApiException.invalid("type must be one of " + types());
    }
    if (status != null
        && !status.isBlank()
        && !List.of("DRAFT", "POSTED", "VOID").contains(status)) {
      throw CatalogApiException.invalid("status must be DRAFT, POSTED, or VOID");
    }
    Instant fromAt = TimeFilter.from(from);
    Instant toAt = TimeFilter.to(to);
    int pageLimit = PageResult.limit(limit);
    int pageOffset = PageResult.offset(offset);
    // Step 2: Newest first, stable by id.
    StringBuilder where = new StringBuilder(" WHERE true");
    List<Object> params = new ArrayList<>();
    if (type != null && !type.isBlank()) {
      where.append(" AND d.type = ?");
      params.add(type);
    }
    if (status != null && !status.isBlank()) {
      where.append(" AND d.status = ?");
      params.add(status);
    }
    if (fromAt != null) {
      where.append(" AND d.created_at >= ?");
      params.add(OffsetDateTime.ofInstant(fromAt, ZoneOffset.UTC));
    }
    if (toAt != null) {
      where.append(" AND d.created_at < ?");
      params.add(OffsetDateTime.ofInstant(toAt, ZoneOffset.UTC));
    }
    return tx.read(
        () -> {
          Long total =
              jdbc.queryForObject(
                  "SELECT count(*) FROM stock_document d" + where, Long.class, params.toArray());
          List<Object> pageParams = new ArrayList<>(params);
          pageParams.add(pageLimit);
          pageParams.add(pageOffset);
          List<StockDocumentView> items =
              jdbc.query(
                  SELECT + where + " ORDER BY d.created_at DESC, d.id DESC LIMIT ? OFFSET ?",
                  (rs, n) -> view(rs),
                  pageParams.toArray());
          return new PageResult<>(items, total == null ? 0 : total, pageLimit, pageOffset);
        });
  }

  public StockDocumentView get(UUID id) {
    return tx.read(() -> load(id));
  }

  // ---- header ------------------------------------------------------------------------------

  public StockDocumentView create(StockDocumentRequest request) {
    // Step 1: Validate before any transaction.
    CatalogAccess.Actor actor = actor();
    if (request == null) {
      throw CatalogApiException.invalid("body is required");
    }
    StockDocumentType type = StockDocumentType.parse(request.type());
    if (type == null) {
      throw CatalogApiException.invalid("type must be one of " + types());
    }
    String reference = reference(request.referenceNo());
    String note = note(request.note());
    // Step 2: Insert the DRAFT and its audit row together.
    return tx.write(
        null,
        () -> {
          UUID id = UuidV7.generate();
          jdbc.update(
              "INSERT INTO stock_document (id, tenant_id, type, status, reference_no, note) "
                  + "VALUES (?, ?, ?, 'DRAFT', ?, ?)",
              id,
              actor.tenantId(),
              type.name(),
              reference,
              note);
          audit.write(
              actor,
              "STOCK_DOCUMENT_CREATED",
              "stock_document",
              id,
              null,
              headerAudit(type.name(), reference, note));
          return load(id);
        });
  }

  public StockDocumentView update(UUID id, StockDocumentRequest request) {
    // Step 1: Only reference and note change. The type is fixed at create.
    CatalogAccess.Actor actor = actor();
    if (request == null) {
      throw CatalogApiException.invalid("body is required");
    }
    String reference = reference(request.referenceNo());
    String note = note(request.note());
    return tx.write(
        null,
        () -> {
          // Step 2: Lock the header like a post would, so it cannot move past DRAFT meanwhile.
          StockDocumentView before = lockDraft(id, true);
          jdbc.update(
              "UPDATE stock_document SET reference_no = ?, note = ?, updated_at = now() "
                  + "WHERE id = ?",
              reference,
              note,
              id);
          audit.write(
              actor,
              "STOCK_DOCUMENT_UPDATED",
              "stock_document",
              id,
              headerAudit(before.type(), before.referenceNo(), before.note()),
              headerAudit(before.type(), reference, note));
          return load(id);
        });
  }

  public void delete(UUID id) {
    CatalogAccess.Actor actor = actor();
    tx.write(
        null,
        () -> {
          // Step 1: DRAFT only (the V4 guard agrees). Lines go first, then the header.
          StockDocumentView before = lockDraft(id, true);
          jdbc.update("DELETE FROM stock_document_line WHERE document_id = ?", id);
          jdbc.update("DELETE FROM stock_document WHERE id = ?", id);
          audit.write(
              actor,
              "STOCK_DOCUMENT_DELETED",
              "stock_document",
              id,
              headerAudit(before.type(), before.referenceNo(), before.note()),
              null);
          return null;
        });
  }

  /**
   * COUNT only: sets {@code count_started_at} and snapshots {@code system_qty_at_start} = current
   * {@code on_hand} (0 without a row) on every line. Lines added later snapshot at insert.
   */
  public StockDocumentView startCount(UUID id) {
    CatalogAccess.Actor actor = actor();
    return tx.write(
        null,
        () -> {
          // Step 1: A DRAFT COUNT that has not started.
          StockDocumentView document = lockDraft(id, true);
          if (!StockDocumentType.COUNT.name().equals(document.type())) {
            throw CatalogApiException.invalid("only a COUNT document can start a count");
          }
          if (document.countStartedAt() != null) {
            throw CatalogApiException.conflict(
                "COUNT_ALREADY_STARTED", "The count was already started");
          }
          // Step 2: Header, then one statement for every line's snapshot (unlocked read).
          Instant startedAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
          jdbc.update(
              "UPDATE stock_document SET count_started_at = ?, updated_at = now() WHERE id = ?",
              OffsetDateTime.ofInstant(startedAt, ZoneOffset.UTC),
              id);
          jdbc.update(
              """
              UPDATE stock_document_line AS l
              SET system_qty_at_start = coalesce(
                    (SELECT i.on_hand FROM inventory i
                     WHERE i.sku_id = l.sku_id AND i.warehouse_id = l.warehouse_id), 0),
                  updated_at = now()
              WHERE l.document_id = ?
              """,
              id);
          audit.write(
              actor,
              "STOCK_DOCUMENT_COUNT_STARTED",
              "stock_document",
              id,
              Map.of("count_started_at", "none"),
              Map.of("count_started_at", startedAt.toString()));
          return load(id);
        });
  }

  // ---- lines -------------------------------------------------------------------------------

  public LineView addLine(UUID documentId, StockDocumentLineRequest request) {
    CatalogAccess.Actor actor = actor();
    UUID warehouseFallback = request != null && request.warehouseId() == null ? fallback() : null;
    return tx.write(
        null,
        () -> {
          // Step 1: The document FOR SHARE, then the line rules for its type.
          Header header = header(lockDraft(documentId, false));
          Long lines =
              jdbc.queryForObject(
                  "SELECT count(*) FROM stock_document_line WHERE document_id = ?",
                  Long.class,
                  documentId);
          if (lines != null && lines >= StockMovements.MAX_LINES) {
            throw CatalogApiException.invalid(
                "a document holds at most " + StockMovements.MAX_LINES + " lines");
          }
          LineInput input = validateLine(header, request, warehouseFallback, null);
          // Step 2: A started count snapshots the line now.
          Integer snapshot =
              header.type() == StockDocumentType.COUNT && header.countStartedAt() != null
                  ? onHand(input.skuId(), input.warehouseId())
                  : null;
          UUID id = UuidV7.generate();
          jdbc.update(
              """
              INSERT INTO stock_document_line
                (id, tenant_id, document_id, sku_id, warehouse_id, qty, system_qty_at_start,
                 counted_qty, reason_code)
              VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
              """,
              id,
              actor.tenantId(),
              documentId,
              input.skuId(),
              input.warehouseId(),
              input.qty(),
              snapshot,
              input.countedQty(),
              input.reasonCode());
          audit.write(
              actor,
              "STOCK_DOCUMENT_LINE_ADDED",
              "stock_document_line",
              id,
              null,
              input.audit(documentId));
          return loadLine(documentId, id);
        });
  }

  public LineView updateLine(UUID documentId, UUID lineId, StockDocumentLineRequest request) {
    CatalogAccess.Actor actor = actor();
    UUID warehouseFallback = request != null && request.warehouseId() == null ? fallback() : null;
    return tx.write(
        null,
        () -> {
          // Step 1: Document FOR SHARE, then the line of this document.
          Header header = header(lockDraft(documentId, false));
          LineView before = loadLine(documentId, lineId);
          LineInput input = validateLine(header, request, warehouseFallback, lineId);
          // Step 2: A started count keeps its snapshot unless the SKU or warehouse moved.
          Integer snapshot = before.systemQtyAtStart();
          if (header.type() == StockDocumentType.COUNT
              && header.countStartedAt() != null
              && (!input.skuId().equals(before.skuId())
                  || !input.warehouseId().equals(before.warehouseId()))) {
            snapshot = onHand(input.skuId(), input.warehouseId());
          }
          jdbc.update(
              """
              UPDATE stock_document_line
              SET sku_id = ?, warehouse_id = ?, qty = ?, system_qty_at_start = ?,
                  counted_qty = ?, reason_code = ?, updated_at = now()
              WHERE id = ? AND document_id = ?
              """,
              input.skuId(),
              input.warehouseId(),
              input.qty(),
              snapshot,
              input.countedQty(),
              input.reasonCode(),
              lineId,
              documentId);
          audit.write(
              actor,
              "STOCK_DOCUMENT_LINE_UPDATED",
              "stock_document_line",
              lineId,
              lineAudit(documentId, before),
              input.audit(documentId));
          return loadLine(documentId, lineId);
        });
  }

  public void deleteLine(UUID documentId, UUID lineId) {
    CatalogAccess.Actor actor = actor();
    tx.write(
        null,
        () -> {
          lockDraft(documentId, false);
          LineView before = loadLine(documentId, lineId);
          jdbc.update(
              "DELETE FROM stock_document_line WHERE id = ? AND document_id = ?",
              lineId,
              documentId);
          audit.write(
              actor,
              "STOCK_DOCUMENT_LINE_DELETED",
              "stock_document_line",
              lineId,
              lineAudit(documentId, before),
              null);
          return null;
        });
  }

  // ---- helpers -----------------------------------------------------------------------------

  /** Type of a visible document, or 404. Read before post/void to pick the role rule. */
  StockDocumentType typeOf(UUID id) {
    return tx.read(() -> StockDocumentType.valueOf(load(id).type()));
  }

  private LineInput validateLine(
      Header header, StockDocumentLineRequest request, UUID warehouseFallback, UUID selfId) {
    // Step 1: SKU by id or code. Bundles have no stock of their own.
    if (request == null) {
      throw CatalogApiException.invalid("body is required");
    }
    UUID skuId = resolveSku(request.skuId(), Fields.trim(request.skuCode()));
    // Step 2: Warehouse: given (and visible) or the default one.
    UUID warehouseId = request.warehouseId() == null ? warehouseFallback : request.warehouseId();
    if (request.warehouseId() != null) {
      Long found =
          jdbc.queryForObject(
              "SELECT count(*) FROM warehouse WHERE id = ?", Long.class, request.warehouseId());
      if (found == null || found == 0) {
        throw new CatalogApiException(422, "UNKNOWN_WAREHOUSE", "Warehouse not found");
      }
    }
    // Step 3: Quantities by type. Drafts may be incomplete; the post applies the full rules.
    int qty = request.qty() == null ? 0 : request.qty();
    if (Math.abs((long) qty) > StockMovements.MAX_QTY) {
      throw CatalogApiException.invalid("qty must be between -1000000 and 1000000");
    }
    Integer counted = request.countedQty();
    if (header.type() == StockDocumentType.COUNT) {
      qty = 0;
      if (counted != null && (counted < 0 || counted > StockMovements.MAX_QTY)) {
        throw CatalogApiException.invalid("counted_qty must be between 0 and 1000000");
      }
    } else if (counted != null) {
      throw CatalogApiException.invalid("counted_qty is only for COUNT documents");
    }
    // Step 4: Reason codes belong to adjustments (and optionally write-offs).
    String reason = Fields.trim(request.reasonCode());
    if (reason != null) {
      if (header.type() != StockDocumentType.ADJUSTMENT
          && header.type() != StockDocumentType.WRITE_OFF) {
        throw CatalogApiException.invalid("reason_code is only for ADJUSTMENT and WRITE_OFF");
      }
      if (AdjustmentReason.parse(reason) == null) {
        throw CatalogApiException.invalid(
            "reason_code must be one of " + Arrays.toString(AdjustmentReason.values()));
      }
    }
    // Step 5: A SKU is counted once per warehouse, or the corrections would add up twice.
    if (header.type() == StockDocumentType.COUNT) {
      Long duplicates =
          jdbc.queryForObject(
              "SELECT count(*) FROM stock_document_line WHERE document_id = ? AND sku_id = ? "
                  + "AND warehouse_id = ? AND id IS DISTINCT FROM ?::uuid",
              Long.class,
              header.id(),
              skuId,
              warehouseId,
              selfId);
      if (duplicates != null && duplicates > 0) {
        throw new CatalogApiException(
            422, "DUPLICATE_LINE", "This SKU is already on the count for that warehouse");
      }
    }
    return new LineInput(skuId, warehouseId, qty, counted, reason);
  }

  private UUID resolveSku(UUID skuId, String skuCode) {
    if (skuId == null && skuCode == null) {
      throw CatalogApiException.invalid("sku_id or sku_code is required");
    }
    List<Map<String, Object>> rows =
        skuId != null
            ? jdbc.queryForList("SELECT id, is_bundle FROM sku WHERE id = ?", skuId)
            : jdbc.queryForList("SELECT id, is_bundle FROM sku WHERE sku_code = ?", skuCode);
    if (rows.isEmpty()) {
      throw new CatalogApiException(422, "UNKNOWN_SKU", "SKU not found");
    }
    if (Boolean.TRUE.equals(rows.get(0).get("is_bundle"))) {
      throw new CatalogApiException(
          422,
          "BUNDLE_NOT_STOCKABLE",
          "A bundle has no stock of its own; add its component SKUs instead");
    }
    return (UUID) rows.get(0).get("id");
  }

  private Integer onHand(UUID skuId, UUID warehouseId) {
    List<Integer> rows =
        jdbc.queryForList(
            "SELECT on_hand FROM inventory WHERE sku_id = ? AND warehouse_id = ?",
            Integer.class,
            skuId,
            warehouseId);
    return rows.isEmpty() ? 0 : rows.get(0);
  }

  /**
   * Locks the header ({@code FOR UPDATE} for header writes, {@code FOR SHARE} for line writes) and
   * requires DRAFT. Unknown and other-tenant ids are 404.
   */
  private StockDocumentView lockDraft(UUID id, boolean exclusive) {
    List<String> status =
        jdbc.queryForList(
            "SELECT status FROM stock_document WHERE id = ? "
                + (exclusive ? "FOR UPDATE" : "FOR SHARE"),
            String.class,
            id);
    if (status.isEmpty()) {
      throw CatalogApiException.notFound("Stock document");
    }
    if (!"DRAFT".equals(status.get(0))) {
      throw CatalogApiException.conflict(
          "DOCUMENT_NOT_DRAFT", "The document is " + status.get(0) + " and can no longer change");
    }
    return load(id).withLines(null);
  }

  private StockDocumentView load(UUID id) {
    List<StockDocumentView> rows = jdbc.query(SELECT + " WHERE d.id = ?", (rs, n) -> view(rs), id);
    if (rows.isEmpty()) {
      throw CatalogApiException.notFound("Stock document");
    }
    List<LineView> lines =
        jdbc.query(
            SELECT_LINES + " WHERE l.document_id = ? ORDER BY l.created_at, l.id",
            (rs, n) -> line(rs),
            id);
    return rows.get(0).withLines(lines);
  }

  private LineView loadLine(UUID documentId, UUID lineId) {
    List<LineView> rows =
        jdbc.query(
            SELECT_LINES + " WHERE l.document_id = ? AND l.id = ?",
            (rs, n) -> line(rs),
            documentId,
            lineId);
    if (rows.isEmpty()) {
      throw CatalogApiException.notFound("Stock document line");
    }
    return rows.get(0);
  }

  private static Header header(StockDocumentView view) {
    return new Header(
        view.id(), StockDocumentType.valueOf(view.type()), view.status(), view.countStartedAt());
  }

  private UUID fallback() {
    return defaultWarehouse.ensure();
  }

  private static CatalogAccess.Actor actor() {
    return new CatalogAccess.Actor(TenantContext.requireTenantId(), TenantContext.requireUserId());
  }

  private static String reference(String value) {
    String trimmed = Fields.trim(value);
    if (trimmed != null && (trimmed.length() > MAX_REFERENCE || hasControl(trimmed))) {
      throw CatalogApiException.invalid(
          "reference_no must be at most " + MAX_REFERENCE + " characters without control codes");
    }
    return trimmed;
  }

  private static String note(String value) {
    String trimmed = Fields.trim(value);
    if (trimmed != null && trimmed.length() > MAX_NOTE) {
      throw CatalogApiException.invalid("note must be at most " + MAX_NOTE + " characters");
    }
    return trimmed;
  }

  private static boolean hasControl(String value) {
    return value.chars().anyMatch(Character::isISOControl);
  }

  /** The note is free text and may name people, so the audit row records only whether it is set. */
  private static Map<String, Object> headerAudit(String type, String reference, String note) {
    Map<String, Object> values = new LinkedHashMap<>();
    values.put("type", type);
    values.put("reference_no", reference);
    values.put("note_set", note != null);
    return values;
  }

  private static Map<String, Object> lineAudit(UUID documentId, LineView line) {
    return new LineInput(
            line.skuId(), line.warehouseId(), line.qty(), line.countedQty(), line.reasonCode())
        .audit(documentId);
  }

  private static String types() {
    return Arrays.toString(StockDocumentType.values());
  }

  private static StockDocumentView view(ResultSet rs) throws SQLException {
    java.sql.Array warehouses = rs.getArray("warehouse_ids");
    List<UUID> warehouseIds = new ArrayList<>();
    if (warehouses != null) {
      for (Object id : (Object[]) warehouses.getArray()) {
        warehouseIds.add((UUID) id);
      }
    }
    return new StockDocumentView(
        rs.getObject("id", UUID.class),
        rs.getString("type"),
        rs.getString("status"),
        rs.getString("reference_no"),
        rs.getString("note"),
        instant(rs, "count_started_at"),
        instant(rs, "posted_at"),
        rs.getObject("posted_by", UUID.class),
        rs.getInt("line_count"),
        List.copyOf(warehouseIds),
        instant(rs, "created_at"),
        instant(rs, "updated_at"),
        null);
  }

  private static LineView line(ResultSet rs) throws SQLException {
    return new LineView(
        rs.getObject("id", UUID.class),
        rs.getObject("sku_id", UUID.class),
        rs.getString("sku_code"),
        rs.getString("sku_name"),
        rs.getObject("warehouse_id", UUID.class),
        rs.getString("warehouse_code"),
        rs.getInt("qty"),
        rs.getObject("system_qty_at_start", Integer.class),
        rs.getObject("counted_qty", Integer.class),
        rs.getString("reason_code"),
        rs.getObject("on_hand", Integer.class),
        rs.getObject("reserved", Integer.class),
        instant(rs, "created_at"));
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    Timestamp value = rs.getTimestamp(column);
    return value == null ? null : value.toInstant();
  }
}
