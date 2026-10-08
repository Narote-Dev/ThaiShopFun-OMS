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
      String skuCode,
      String skuName,
      String mappingSource,
      Instant mappedAt,
      boolean stockControl,
      Instant removedAt,
      long heldOrders) {}

  public record UpsertResult(
      UUID id, boolean inserted, boolean mappingChanged, boolean newlyMapped, boolean revived) {}

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
        ) VALUES (?, ?, ?, ?, ?, ?, false, NULL)
        ON CONFLICT (channel_account_id, external_sku_id) DO NOTHING
        """,
        UuidV7.generate(),
        tenantId,
        channelAccountId,
        externalSkuId,
        sellerSku,
        name);
  }

  public Optional<ListingRow> findByIdForUpdate(UUID id) {
    List<ListingRow> rows =
        jdbc.query(
            """
            SELECT cl.id, cl.channel_account_id, cl.external_sku_id, cl.seller_sku, cl.name,
                   cl.sku_id, s.sku_code, s.name AS sku_name, cl.mapping_source, cl.mapped_at,
                   cl.stock_control, cl.removed_at,
                   (
                     SELECT count(DISTINCT so.id) FROM sales_order so
                     JOIN order_line ol ON ol.order_id = so.id
                     WHERE so.channel_account_id = cl.channel_account_id
                       AND ol.external_sku_id = cl.external_sku_id
                       AND so.hold_reason = 'SKU_NOT_MAPPED'
                       AND so.order_status = 'ACTIVE'
                   ) AS held_orders
            FROM channel_listing cl
            LEFT JOIN sku s ON s.tenant_id = cl.tenant_id AND s.id = cl.sku_id
            WHERE cl.id = ?
            FOR UPDATE OF cl
            """,
            this::map,
            id);
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
  }

  public Optional<ListingRow> findById(UUID id) {
    List<ListingRow> rows =
        jdbc.query(
            """
            SELECT cl.id, cl.channel_account_id, cl.external_sku_id, cl.seller_sku, cl.name,
                   cl.sku_id, s.sku_code, s.name AS sku_name, cl.mapping_source, cl.mapped_at,
                   cl.stock_control, cl.removed_at,
                   (
                     SELECT count(DISTINCT so.id) FROM sales_order so
                     JOIN order_line ol ON ol.order_id = so.id
                     WHERE so.channel_account_id = cl.channel_account_id
                       AND ol.external_sku_id = cl.external_sku_id
                       AND so.hold_reason = 'SKU_NOT_MAPPED'
                       AND so.order_status = 'ACTIVE'
                   ) AS held_orders
            FROM channel_listing cl
            LEFT JOIN sku s ON s.tenant_id = cl.tenant_id AND s.id = cl.sku_id
            WHERE cl.id = ?
            """,
            this::map,
            id);
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
  }

  public List<ListingRow> list(
      UUID channelAccountId, Boolean mapped, Boolean removedOnly, String q, int limit, int offset) {
    StringBuilder sql =
        new StringBuilder(
            """
            SELECT cl.id, cl.channel_account_id, cl.external_sku_id, cl.seller_sku, cl.name,
                   cl.sku_id, s.sku_code, s.name AS sku_name, cl.mapping_source, cl.mapped_at,
                   cl.stock_control, cl.removed_at,
                   (
                     SELECT count(DISTINCT so.id) FROM sales_order so
                     JOIN order_line ol ON ol.order_id = so.id
                     WHERE so.channel_account_id = cl.channel_account_id
                       AND ol.external_sku_id = cl.external_sku_id
                       AND so.hold_reason = 'SKU_NOT_MAPPED'
                       AND so.order_status = 'ACTIVE'
                   ) AS held_orders
            FROM channel_listing cl
            LEFT JOIN sku s ON s.tenant_id = cl.tenant_id AND s.id = cl.sku_id
            WHERE cl.channel_account_id = ?
            """);
    java.util.List<Object> args = new java.util.ArrayList<>();
    args.add(channelAccountId);
    if (Boolean.TRUE.equals(removedOnly)) {
      sql.append(" AND cl.removed_at IS NOT NULL ");
    }
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

  public long count(UUID channelAccountId, Boolean mapped, Boolean removedOnly, String q) {
    StringBuilder sql =
        new StringBuilder(
            """
            SELECT count(*) FROM channel_listing cl
            WHERE cl.channel_account_id = ?
            """);
    java.util.List<Object> args = new java.util.ArrayList<>();
    args.add(channelAccountId);
    if (Boolean.TRUE.equals(removedOnly)) {
      sql.append(" AND cl.removed_at IS NOT NULL ");
    }
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
      UUID tenantId, UUID channelAccountId, String externalSkuId, String sellerSku, String name) {
    java.util.Optional<java.time.OffsetDateTime> removedBefore =
        jdbc.query(
            """
            SELECT removed_at FROM channel_listing
            WHERE channel_account_id = ? AND external_sku_id = ?
            """,
            rs -> {
              if (!rs.next()) {
                return java.util.Optional.empty();
              }
              return java.util.Optional.ofNullable(
                  rs.getObject("removed_at", OffsetDateTime.class));
            },
            channelAccountId,
            externalSkuId);
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
              sku_id, mapping_source, mapped_at, removed_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, NULL)
            ON CONFLICT (channel_account_id, external_sku_id) DO UPDATE SET
              seller_sku = COALESCE(NULLIF(EXCLUDED.seller_sku, ''), channel_listing.seller_sku),
              name = COALESCE(NULLIF(EXCLUDED.name, ''), channel_listing.name),
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
            sellerSku == null ? "" : sellerSku,
            name == null ? "" : name,
            autoSku,
            autoSku == null ? null : "AUTO",
            mappedAt);
    UUID id =
        jdbc.queryForObject(
            "SELECT id FROM channel_listing WHERE channel_account_id = ? AND external_sku_id = ?",
            UUID.class,
            channelAccountId,
            externalSkuId);
    UUID newSku =
        jdbc.queryForObject("SELECT sku_id FROM channel_listing WHERE id = ?", UUID.class, id);
    boolean mappingChanged = existingSku == null && newSku != null;
    boolean newlyMapped = existingSku == null && newSku != null;
    boolean revived = removedBefore.isPresent() && removedBefore.get() != null;
    return new UpsertResult(
        id,
        Boolean.TRUE.equals(inserted),
        mappingChanged || (existingSku == null && newSku != null),
        newlyMapped,
        revived);
  }

  public long countActive(UUID channelAccountId) {
    Long count =
        jdbc.queryForObject(
            """
            SELECT count(*) FROM channel_listing
            WHERE channel_account_id = ? AND removed_at IS NULL
            """,
            Long.class,
            channelAccountId);
    return count == null ? 0 : count;
  }

  public long countVanishedCandidates(
      UUID channelAccountId, java.util.Set<String> fetchedIds, Instant syncStartedAt) {
    if (fetchedIds.isEmpty()) {
      return 0;
    }
    String[] ids = fetchedIds.toArray(String[]::new);
    Long count =
        jdbc.query(
            """
            SELECT count(*) FROM channel_listing
            WHERE channel_account_id = ?
              AND removed_at IS NULL
              AND updated_at < ?
              AND NOT (external_sku_id = ANY (?))
            """,
            ps -> {
              ps.setObject(1, channelAccountId);
              ps.setObject(2, OffsetDateTime.ofInstant(syncStartedAt, java.time.ZoneOffset.UTC));
              ps.setArray(3, ps.getConnection().createArrayOf("text", ids));
            },
            rs -> {
              rs.next();
              return rs.getLong(1);
            });
    return count == null ? 0 : count;
  }

  public int markVanished(
      UUID channelAccountId, java.util.Set<String> fetchedIds, Instant syncStartedAt) {
    if (fetchedIds.isEmpty()) {
      return 0;
    }
    String[] ids = fetchedIds.toArray(String[]::new);
    return jdbc.update(
        """
        UPDATE channel_listing
        SET removed_at = now(), updated_at = now()
        WHERE channel_account_id = ?
          AND removed_at IS NULL
          AND updated_at < ?
          AND NOT (external_sku_id = ANY (?))
        """,
        ps -> {
          ps.setObject(1, channelAccountId);
          ps.setObject(2, OffsetDateTime.ofInstant(syncStartedAt, java.time.ZoneOffset.UTC));
          ps.setArray(3, ps.getConnection().createArrayOf("text", ids));
        });
  }

  public long countHeldOrdersForListing(UUID channelAccountId, String externalSkuId) {
    Long count =
        jdbc.queryForObject(
            """
            SELECT count(DISTINCT so.id)
            FROM sales_order so
            JOIN order_line ol ON ol.order_id = so.id
            WHERE so.channel_account_id = ?
              AND ol.external_sku_id = ?
              AND so.hold_reason = 'SKU_NOT_MAPPED'
              AND so.order_status = 'ACTIVE'
            """,
            Long.class,
            channelAccountId,
            externalSkuId);
    return count == null ? 0 : count;
  }

  public void markRemoved(UUID tenantId, UUID channelAccountId, String externalSkuId) {
    jdbc.update(
        """
        UPDATE channel_listing
        SET removed_at = now(), updated_at = now()
        WHERE tenant_id = ? AND channel_account_id = ? AND external_sku_id = ?
        """,
        tenantId,
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

  public List<UUID> findSkuNotMappedOrderIdsForListing(
      UUID channelAccountId, String externalSkuId, int limit) {
    return jdbc.query(
        """
        SELECT so.id
        FROM sales_order so
        WHERE so.channel_account_id = ?
          AND so.order_status = 'ACTIVE'
          AND so.fulfillment_status = 'UNFULFILLED'
          AND so.hold_reason = 'SKU_NOT_MAPPED'
          AND EXISTS (
            SELECT 1 FROM order_line ol
            WHERE ol.order_id = so.id AND ol.external_sku_id = ?
          )
        ORDER BY so.ordered_at, so.id
        LIMIT ?
        """,
        (rs, row) -> rs.getObject("id", UUID.class),
        channelAccountId,
        externalSkuId,
        limit);
  }

  public List<UUID> findResolvableSkuNotMappedOrderIds(UUID tenantId, int limit, Instant asOf) {
    return jdbc.query(
        """
        SELECT so.id
        FROM sales_order so
        WHERE so.tenant_id = ?
          AND so.order_status = 'ACTIVE'
          AND so.fulfillment_status = 'UNFULFILLED'
          AND so.hold_reason = 'SKU_NOT_MAPPED'
          AND NOT EXISTS (
            SELECT 1 FROM order_line ol
            LEFT JOIN channel_listing cl
              ON cl.channel_account_id = so.channel_account_id
             AND cl.external_sku_id = ol.external_sku_id
             AND cl.removed_at IS NULL
             AND cl.sku_id IS NOT NULL
            WHERE ol.order_id = so.id
              AND cl.id IS NULL
          )
          AND NOT EXISTS (
            SELECT 1 FROM order_hold_retry r
            WHERE r.tenant_id = so.tenant_id
              AND r.order_id = so.id
              AND r.next_attempt_at > ?
          )
        ORDER BY so.ordered_at, so.id
        LIMIT ?
        """,
        (rs, row) -> rs.getObject("id", UUID.class),
        tenantId,
        OffsetDateTime.ofInstant(asOf, java.time.ZoneOffset.UTC),
        limit);
  }

  public List<UUID> listActiveTenantIds() {
    return jdbc.query(
        "SELECT id FROM list_active_tenant_ids()", (rs, row) -> rs.getObject("id", UUID.class));
  }

  private UUID tryAutoMapSku(UUID tenantId, String sellerSku) {
    if (sellerSku == null || sellerSku.isBlank()) {
      return null;
    }
    String code = sellerSku.trim();
    List<UUID> ids =
        jdbc.query(
            "SELECT id FROM sku WHERE tenant_id = ? AND sku_code = ? LIMIT 1",
            (rs, row) -> rs.getObject("id", UUID.class),
            tenantId,
            code);
    return ids.isEmpty() ? null : ids.get(0);
  }

  private ListingRow map(ResultSet rs, int rowNum) throws SQLException {
    java.time.OffsetDateTime removed = rs.getObject("removed_at", java.time.OffsetDateTime.class);
    java.time.OffsetDateTime mapped = rs.getObject("mapped_at", java.time.OffsetDateTime.class);
    return new ListingRow(
        rs.getObject("id", UUID.class),
        rs.getObject("channel_account_id", UUID.class),
        rs.getString("external_sku_id"),
        rs.getString("seller_sku"),
        rs.getString("name"),
        rs.getObject("sku_id", UUID.class),
        rs.getString("sku_code"),
        rs.getString("sku_name"),
        rs.getString("mapping_source"),
        mapped == null ? null : mapped.toInstant(),
        rs.getBoolean("stock_control"),
        removed == null ? null : removed.toInstant(),
        rs.getLong("held_orders"));
  }
}
