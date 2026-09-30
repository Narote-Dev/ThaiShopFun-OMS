package com.thaishopfun.oms.stockdoc;

import com.thaishopfun.oms.catalog.CatalogApiException;
import com.thaishopfun.oms.catalog.CatalogTransactions;
import com.thaishopfun.oms.catalog.PageResult;
import com.thaishopfun.oms.stockdoc.StockHistoryPage.Entry;
import com.thaishopfun.oms.stockdoc.StockHistoryPage.Link;
import com.thaishopfun.oms.stockdoc.StockHistoryPage.SkuRef;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Per-SKU stock history: {@code inventory_ledger} newest first, keyset-paged on {@code (created_at,
 * id)}. Running totals come from a window over the SKU's whole ledger (per warehouse), so they stay
 * correct under the reason and date filters. That window reads every ledger row of one SKU per
 * request, which is fine at shop volumes; a snapshot table would replace it if a single SKU ever
 * reaches hundreds of thousands of entries.
 */
@Service
public class StockHistoryService {

  static final Set<String> REASONS =
      Set.of(
          "OPENING_BALANCE",
          "RECEIVE",
          "ADJUST_IN",
          "ADJUST_OUT",
          "COUNT_CORRECTION",
          "DAMAGE_WRITE_OFF",
          "RETURN_RESTOCK",
          "SHIP",
          "RESERVE",
          "RELEASE",
          "UNPACK");

  private final JdbcTemplate jdbc;
  private final CatalogTransactions tx;

  public StockHistoryService(JdbcTemplate jdbc, CatalogTransactions tx) {
    this.jdbc = jdbc;
    this.tx = tx;
  }

  private record Cursor(Instant createdAt, UUID id) {}

  public StockHistoryPage history(
      UUID skuId,
      UUID warehouseId,
      String reason,
      String from,
      String to,
      String cursor,
      Integer limit) {
    // Step 1: Validate the filters before the query.
    if (reason != null && !reason.isBlank() && !REASONS.contains(reason)) {
      throw CatalogApiException.invalid("reason is not a ledger reason");
    }
    Instant fromAt = TimeFilter.from(from);
    Instant toAt = TimeFilter.to(to);
    Cursor after = decode(cursor);
    int pageLimit = PageResult.limit(limit);
    return tx.read(
        () -> {
          // Step 2: The SKU must be visible. A bundle has no ledger of its own.
          List<Map<String, Object>> skus =
              jdbc.queryForList(
                  "SELECT id, sku_code, name, is_bundle FROM sku WHERE id = ?", skuId);
          if (skus.isEmpty()) {
            throw CatalogApiException.notFound("SKU");
          }
          Map<String, Object> sku = skus.get(0);
          if (Boolean.TRUE.equals(sku.get("is_bundle"))) {
            throw new CatalogApiException(
                422,
                "BUNDLE_NOT_STOCKABLE",
                "A bundle has no stock of its own; open the history of its component SKUs");
          }
          // Step 3: Running totals over the whole ledger, then filters, then the keyset page.
          StringBuilder sql =
              new StringBuilder(
                  """
                  WITH l AS (
                    SELECT l.id, l.created_at, l.warehouse_id, l.reason, l.delta_on_hand,
                           l.delta_reserved, l.actor, l.ref_type, l.ref_id,
                           sum(l.delta_on_hand) OVER w AS on_hand_after,
                           sum(l.delta_reserved) OVER w AS reserved_after
                    FROM inventory_ledger l
                    WHERE l.sku_id = ?
                    WINDOW w AS (PARTITION BY l.warehouse_id ORDER BY l.created_at, l.id
                                 ROWS UNBOUNDED PRECEDING)
                  )
                  SELECT l.*, w.code AS warehouse_code,
                         dl.document_id, d.type AS document_type, d.status AS document_status,
                         d.reference_no, r.reservation_group_id, r.owner_type, r.owner_ref
                  FROM l
                  JOIN warehouse w ON w.id = l.warehouse_id
                  LEFT JOIN stock_document_line dl
                    ON l.ref_type = 'stock_document_line' AND dl.id = l.ref_id
                  LEFT JOIN stock_document d ON d.id = dl.document_id
                  LEFT JOIN stock_reservation r
                    ON l.ref_type = 'stock_reservation' AND r.id = l.ref_id
                  WHERE true
                  """);
          List<Object> params = new ArrayList<>();
          params.add(skuId);
          if (warehouseId != null) {
            sql.append(" AND l.warehouse_id = ?");
            params.add(warehouseId);
          }
          if (reason != null && !reason.isBlank()) {
            sql.append(" AND l.reason = ?");
            params.add(reason);
          }
          if (fromAt != null) {
            sql.append(" AND l.created_at >= ?");
            params.add(OffsetDateTime.ofInstant(fromAt, ZoneOffset.UTC));
          }
          if (toAt != null) {
            sql.append(" AND l.created_at < ?");
            params.add(OffsetDateTime.ofInstant(toAt, ZoneOffset.UTC));
          }
          if (after != null) {
            sql.append(" AND (l.created_at, l.id) < (?, ?)");
            params.add(OffsetDateTime.ofInstant(after.createdAt(), ZoneOffset.UTC));
            params.add(after.id());
          }
          sql.append(" ORDER BY l.created_at DESC, l.id DESC LIMIT ?");
          params.add(pageLimit + 1);
          List<Entry> rows = jdbc.query(sql.toString(), (rs, n) -> entry(rs), params.toArray());
          // Step 4: One extra row tells whether another page exists.
          String next = null;
          if (rows.size() > pageLimit) {
            rows = rows.subList(0, pageLimit);
            Entry last = rows.get(rows.size() - 1);
            next = encode(new Cursor(last.createdAt(), last.id()));
          }
          return new StockHistoryPage(
              new SkuRef(skuId, (String) sku.get("sku_code"), (String) sku.get("name")),
              List.copyOf(rows),
              next);
        });
  }

  private static Entry entry(ResultSet rs) throws SQLException {
    String refType = rs.getString("ref_type");
    UUID refId = rs.getObject("ref_id", UUID.class);
    return new Entry(
        rs.getObject("id", UUID.class),
        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
        rs.getObject("warehouse_id", UUID.class),
        rs.getString("warehouse_code"),
        rs.getString("reason"),
        rs.getInt("delta_on_hand"),
        rs.getInt("delta_reserved"),
        rs.getLong("on_hand_after"),
        rs.getLong("reserved_after"),
        rs.getString("actor"),
        refType,
        refId,
        link(rs, refType, refId));
  }

  private static Link link(ResultSet rs, String refType, UUID refId) throws SQLException {
    if (refType == null) {
      return null;
    }
    return switch (refType) {
      case "stock_document_line" ->
          new Link(
              "stock_document",
              rs.getObject("document_id", UUID.class),
              rs.getString("document_type"),
              rs.getString("document_status"),
              rs.getString("reference_no"),
              null,
              null,
              null,
              null);
      case "stock_reservation" -> {
        String ownerType = rs.getString("owner_type");
        yield new Link(
            "reservation",
            null,
            null,
            null,
            null,
            rs.getObject("reservation_group_id", UUID.class),
            ownerType,
            "ORDER".equals(ownerType) ? rs.getString("owner_ref") : null,
            null);
      }
      case "return_line" ->
          new Link("return_line", null, null, null, null, null, null, null, refId);
      default -> null;
    };
  }

  private static String encode(Cursor cursor) {
    long micros = ChronoUnit.MICROS.between(Instant.EPOCH, cursor.createdAt());
    String raw = micros + ":" + cursor.id();
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
  }

  private static Cursor decode(String cursor) {
    if (cursor == null || cursor.isBlank()) {
      return null;
    }
    try {
      String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
      int colon = raw.indexOf(':');
      long micros = Long.parseLong(raw.substring(0, colon));
      return new Cursor(
          Instant.EPOCH.plus(micros, ChronoUnit.MICROS), UUID.fromString(raw.substring(colon + 1)));
    } catch (RuntimeException ex) {
      throw CatalogApiException.invalid("cursor is invalid");
    }
  }
}
