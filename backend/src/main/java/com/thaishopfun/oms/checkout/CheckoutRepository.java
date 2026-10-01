package com.thaishopfun.oms.checkout;

import com.thaishopfun.oms.auth.UuidV7;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;

@Repository
class CheckoutRepository {

  record TenantRow(String entitlementStatus, Instant entitlementExpiresAt, String tsfShopId) {}

  record ChannelAccount(UUID id, String mode, String status, boolean stockSyncPaused) {}

  record ListingRow(UUID id, UUID skuId, boolean stockControl, int safetyBuffer) {}

  private final JdbcTemplate jdbc;

  CheckoutRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  UUID resolveTenant(String channel, String externalShopId) {
    List<UUID> ids =
        jdbc.query(
            "SELECT resolve_tenant(?, ?)",
            (rs, row) -> rs.getObject(1, UUID.class),
            channel,
            externalShopId);
    return ids.isEmpty() ? null : ids.get(0);
  }

  UUID resolveReservationTenant(UUID reservationGroupId) {
    List<UUID> ids =
        jdbc.query(
            "SELECT resolve_reservation_tenant(?)",
            (rs, row) -> rs.getObject(1, UUID.class),
            reservationGroupId);
    return ids.isEmpty() ? null : ids.get(0);
  }

  Optional<TenantRow> tenant(UUID tenantId) {
    List<TenantRow> rows =
        jdbc.query(
            """
            SELECT entitlement_status, entitlement_expires_at, tsf_shop_id
            FROM tenant WHERE id = ?
            """,
            (rs, row) ->
                new TenantRow(
                    rs.getString("entitlement_status"),
                    instant(rs.getObject("entitlement_expires_at", OffsetDateTime.class)),
                    rs.getString("tsf_shop_id")),
            tenantId);
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
  }

  Optional<ChannelAccount> tsfAccount(UUID tenantId) {
    List<ChannelAccount> rows =
        jdbc.query(
            """
            SELECT id, mode, status, stock_sync_paused
            FROM channel_account
            WHERE tenant_id = ? AND channel = 'TSF'
            ORDER BY created_at
            LIMIT 1
            """,
            (rs, row) ->
                new ChannelAccount(
                    rs.getObject("id", UUID.class),
                    rs.getString("mode"),
                    rs.getString("status"),
                    rs.getBoolean("stock_sync_paused")),
            tenantId);
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
  }

  boolean hasDefaultWarehouse() {
    List<Integer> rows =
        jdbc.queryForList("SELECT 1 FROM warehouse WHERE is_default LIMIT 1", Integer.class);
    return !rows.isEmpty();
  }

  Optional<ListingRow> listing(UUID tenantId, UUID channelAccountId, String externalSkuId) {
    List<ListingRow> rows =
        jdbc.query(
            """
            SELECT id, sku_id, stock_control, safety_buffer
            FROM channel_listing
            WHERE tenant_id = ? AND channel_account_id = ? AND external_sku_id = ?
            """,
            (rs, row) ->
                new ListingRow(
                    rs.getObject("id", UUID.class),
                    rs.getObject("sku_id", UUID.class),
                    rs.getBoolean("stock_control"),
                    rs.getInt("safety_buffer")),
            tenantId,
            channelAccountId,
            externalSkuId);
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
  }

  void insertShadowDiff(
      UUID tenantId,
      UUID channelAccountId,
      String checkoutId,
      JsonNode omsValue,
      Instant observedAt) {
    jdbc.update(
        """
        INSERT INTO shadow_diff
          (id, tenant_id, channel_account_id, kind, ref, oms_value, channel_value, observed_at)
        VALUES (?, ?, ?, 'RESERVATION', ?, ?::jsonb, NULL, ?)
        """,
        UuidV7.generate(),
        tenantId,
        channelAccountId,
        checkoutId,
        omsValue.toString(),
        OffsetDateTime.ofInstant(observedAt, ZoneOffset.UTC));
  }

  private static Instant instant(OffsetDateTime value) {
    return value == null ? null : value.toInstant();
  }
}
