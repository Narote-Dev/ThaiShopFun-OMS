package com.thaishopfun.oms.listing;

import com.thaishopfun.oms.auth.UuidV7;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ChannelListingRepository {

  public record ListingRow(
      UUID id,
      UUID channelAccountId,
      String externalSkuId,
      String sellerSku,
      String name,
      UUID skuId,
      String mappingSource,
      Instant mappedAt,
      boolean stockControl,
      long heldOrders) {}

  public record UpsertResult(UUID id, boolean mappingChanged, boolean newlyMapped) {}

  private final JdbcTemplate jdbc;

  public ChannelListingRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** Ensures a listing row exists for intake / demo lines that reference an external sku id. */
  public void ensureStub(
      UUID tenantId, UUID channelAccountId, String externalSkuId, String sellerSku, String name) {
    jdbc.update(
        """
        INSERT INTO channel_listing (
          id, tenant_id, channel_account_id, external_sku_id, seller_sku, name, stock_control, removed_at
        ) VALUES (?, ?, ?, ?, ?, ?, true, NULL)
        ON CONFLICT (channel_account_id, external_sku_id) DO UPDATE SET
          seller_sku = COALESCE(EXCLUDED.seller_sku, channel_listing.seller_sku),
          name = COALESCE(EXCLUDED.name, channel_listing.name),
          removed_at = NULL,
          updated_at = now()
        """,
        UuidV7.generate(),
        tenantId,
        channelAccountId,
        externalSkuId,
        sellerSku,
        name);
  }

  public Optional<ListingRow> findById(UUID id) {
    List<ListingRow> rows =
        jdbc.query(
            """
            SELECT cl.id, cl.channel_account_id, cl.external_sku_id, cl.seller_sku, cl.name,
                   cl.sku_id, cl.mapping_source, cl.mapped_at, cl.stock_control,
                   (
                     SELECT count(*) FROM sales_order so
                     JOIN order_line ol ON ol.order_id = so.id
                     WHERE so.channel_account_id = cl.channel_account_id
                       AND ol.external_sku_id = cl.external_sku_id
                       AND so.hold_reason IN ('SKU_NOT_MAPPED', 'OUT_OF_STOCK')
                       AND so.order_status = 'ACTIVE'
                   ) AS held_orders
            FROM channel_listing cl
            WHERE cl.id = ?
            """,
            this::map,
            id);
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
  }

  public List<ListingRow> list(
      UUID channelAccountId, Boolean mapped, String q, int limit, int offset) {
    StringBuilder sql =
        new StringBuilder(
            """
            SELECT cl.id, cl.channel_account_id, cl.external_sku_id, cl.seller_sku, cl.name,
                   cl.sku_id, cl.mapping_source, cl.mapped_at, cl.stock_control,
                   (
                     SELECT count(*) FROM sales_order so
                     JOIN order_line ol ON ol.order_id = so.id
                     WHERE so.channel_account_id = cl.channel_account_id
                       AND ol.external_sku_id = cl.external_sku_id
                       AND so.hold_reason IN ('SKU_NOT_MAPPED', 'OUT_OF_STOCK')
                       AND so.order_status = 'ACTIVE'
                   ) AS held_orders
            FROM channel_listing cl
            WHERE cl.channel_account_id = ?
              AND cl.removed_at IS NULL
            """);
    java.util.List<Object> args = new java.util.ArrayList<>();
    args.add(channelAccountId);
    if (mapped != null) {
      sql.append(mapped ? " AND cl.sku_id IS NOT NULL " : " AND cl.sku_id IS NULL ");
    }
    if (q != null && !q.isBlank()) {
      sql.append(" AND (cl.external_sku_id ILIKE ? OR cl.seller_sku ILIKE ? OR cl.name ILIKE ?) ");
      String pattern = "%" + q.trim() + "%";
      args.add(pattern);
      args.add(pattern);
      args.add(pattern);
    }
    sql.append(" ORDER BY cl.external_sku_id, cl.id LIMIT ? OFFSET ?");
    args.add(limit);
    args.add(offset);
    return jdbc.query(sql.toString(), this::map, args.toArray());
  }

  public long count(UUID channelAccountId, Boolean mapped, String q) {
    StringBuilder sql =
        new StringBuilder(
            """
            SELECT count(*) FROM channel_listing cl
            WHERE cl.channel_account_id = ? AND cl.removed_at IS NULL
            """);
    java.util.List<Object> args = new java.util.ArrayList<>();
    args.add(channelAccountId);
    if (mapped != null) {
      sql.append(mapped ? " AND cl.sku_id IS NOT NULL " : " AND cl.sku_id IS NULL ");
    }
    if (q != null && !q.isBlank()) {
      sql.append(" AND (cl.external_sku_id ILIKE ? OR cl.seller_sku ILIKE ? OR cl.name ILIKE ?) ");
      String pattern = "%" + q.trim() + "%";
      args.add(pattern);
      args.add(pattern);
      args.add(pattern);
    }
    Long total = jdbc.queryForObject(sql.toString(), Long.class, args.toArray());
    return total == null ? 0 : total;
  }

  public UpsertResult upsertFromChannel(
      UUID tenantId,
      UUID channelAccountId,
      String externalSkuId,
      String sellerSku,
      String name,
      Integer lastSeenQty,
      Long stockVersion) {
    UUID existingSku =
        jdbc.query(
            """
            SELECT sku_id FROM channel_listing
            WHERE channel_account_id = ? AND external_sku_id = ?
            """,
            rs -> rs.next() ? rs.getObject("sku_id", UUID.class) : null,
            channelAccountId,
            externalSkuId);
    UUID autoSku = tryAutoMapSku(tenantId, sellerSku);
    OffsetDateTime mappedAt = autoSku == null ? null : OffsetDateTime.now();
    Boolean inserted =
        jdbc.query(
            """
            INSERT INTO channel_listing (
              id, tenant_id, channel_account_id, external_sku_id, seller_sku, name,
              sku_id, mapping_source, mapped_at, last_seen_channel_qty, last_pushed_version,
              removed_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL)
            ON CONFLICT (channel_account_id, external_sku_id) DO UPDATE SET
              seller_sku = EXCLUDED.seller_sku,
              name = EXCLUDED.name,
              last_seen_channel_qty = EXCLUDED.last_seen_channel_qty,
              last_pushed_version = COALESCE(EXCLUDED.last_pushed_version, channel_listing.last_pushed_version),
              removed_at = NULL,
              updated_at = now(),
              sku_id = COALESCE(channel_listing.sku_id, EXCLUDED.sku_id),
              mapping_source = COALESCE(channel_listing.mapping_source, EXCLUDED.mapping_source),
              mapped_at = COALESCE(channel_listing.mapped_at, EXCLUDED.mapped_at)
            RETURNING (xmax = 0) AS inserted
            """,
            rs -> {
              rs.next();
              return rs.getBoolean("inserted");
            },
            UuidV7.generate(),
            tenantId,
            channelAccountId,
            externalSkuId,
            sellerSku,
            name,
            autoSku,
            autoSku == null ? null : "AUTO",
            mappedAt,
            lastSeenQty,
            stockVersion);
    UUID id =
        jdbc.queryForObject(
            "SELECT id FROM channel_listing WHERE channel_account_id = ? AND external_sku_id = ?",
            UUID.class,
            channelAccountId,
            externalSkuId);
    UUID newSku =
        jdbc.queryForObject("SELECT sku_id FROM channel_listing WHERE id = ?", UUID.class, id);
    boolean mappingChanged = existingSku == null && newSku != null;
    boolean newlyMapped = Boolean.TRUE.equals(inserted) && newSku != null;
    return new UpsertResult(
        id, mappingChanged || (existingSku == null && newSku != null), newlyMapped);
  }

  public void markRemoved(UUID channelAccountId, String externalSkuId) {
    jdbc.update(
        """
        UPDATE channel_listing
        SET removed_at = now(), updated_at = now()
        WHERE channel_account_id = ? AND external_sku_id = ?
        """,
        channelAccountId,
        externalSkuId);
  }

  public void putManualMapping(UUID listingId, UUID skuId) {
    jdbc.update(
        """
        UPDATE channel_listing
        SET sku_id = ?, mapping_source = 'MANUAL', mapped_at = now(), updated_at = now()
        WHERE id = ?
        """,
        skuId,
        listingId);
  }

  public void clearMapping(UUID listingId) {
    jdbc.update(
        """
        UPDATE channel_listing
        SET sku_id = NULL, mapping_source = NULL, mapped_at = NULL, updated_at = now()
        WHERE id = ?
        """,
        listingId);
  }

  public List<UUID> findHeldOrderIdsForListing(
      UUID channelAccountId, String externalSkuId, int limit) {
    return jdbc.query(
        """
        SELECT DISTINCT so.id
        FROM sales_order so
        JOIN order_line ol ON ol.order_id = so.id
        WHERE so.channel_account_id = ?
          AND ol.external_sku_id = ?
          AND so.order_status = 'ACTIVE'
          AND so.hold_reason IN ('SKU_NOT_MAPPED', 'OUT_OF_STOCK')
        ORDER BY so.id
        LIMIT ?
        """,
        (rs, row) -> rs.getObject("id", UUID.class),
        channelAccountId,
        externalSkuId,
        limit);
  }

  public List<UUID> findHeldOrderIds(UUID tenantId, int limit) {
    return jdbc.query(
        """
        SELECT id FROM sales_order
        WHERE tenant_id = ?
          AND order_status = 'ACTIVE'
          AND hold_reason IN ('SKU_NOT_MAPPED', 'OUT_OF_STOCK')
        ORDER BY ordered_at, id
        LIMIT ?
        """,
        (rs, row) -> rs.getObject("id", UUID.class),
        tenantId,
        limit);
  }

  public List<UUID> tenantsWithResolvableHolds(int limit) {
    return jdbc.query(
        """
        SELECT DISTINCT tenant_id FROM sales_order
        WHERE order_status = 'ACTIVE'
          AND hold_reason IN ('SKU_NOT_MAPPED', 'OUT_OF_STOCK')
        LIMIT ?
        """,
        (rs, row) -> rs.getObject("tenant_id", UUID.class),
        limit);
  }

  private UUID tryAutoMapSku(UUID tenantId, String sellerSku) {
    if (sellerSku == null || sellerSku.isBlank()) {
      return null;
    }
    List<UUID> ids =
        jdbc.query(
            "SELECT id FROM sku WHERE tenant_id = ? AND sku_code = ? LIMIT 1",
            (rs, row) -> rs.getObject("id", UUID.class),
            tenantId,
            sellerSku);
    return ids.isEmpty() ? null : ids.get(0);
  }

  private ListingRow map(ResultSet rs, int rowNum) throws SQLException {
    return new ListingRow(
        rs.getObject("id", UUID.class),
        rs.getObject("channel_account_id", UUID.class),
        rs.getString("external_sku_id"),
        rs.getString("seller_sku"),
        rs.getString("name"),
        rs.getObject("sku_id", UUID.class),
        rs.getString("mapping_source"),
        rs.getObject("mapped_at", java.time.OffsetDateTime.class) == null
            ? null
            : rs.getObject("mapped_at", java.time.OffsetDateTime.class).toInstant(),
        rs.getBoolean("stock_control"),
        rs.getLong("held_orders"));
  }
}
