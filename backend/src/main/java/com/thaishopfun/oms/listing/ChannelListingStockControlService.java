package com.thaishopfun.oms.listing;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
class ChannelListingStockControlService {
  private final ChannelListingAccess access;
  private final ListingTransactions tx;
  private final ChannelListingRepository listings;
  private final ListingAudit audit;
  private final JdbcTemplate jdbc;

  ChannelListingStockControlService(
      ChannelListingAccess access,
      ListingTransactions tx,
      ChannelListingRepository listings,
      ListingAudit audit,
      JdbcTemplate jdbc) {
    this.access = access;
    this.tx = tx;
    this.listings = listings;
    this.audit = audit;
    this.jdbc = jdbc;
  }

  ChannelListingViews.StockControlResult change(UUID accountId, List<UUID> ids, Boolean enabled) {
    var actor = access.requireWriter();
    if (enabled == null
        || ids == null
        || ids.isEmpty()
        || ids.size() > 500
        || ids.stream().anyMatch(java.util.Objects::isNull)) {
      throw new ListingApiException(
          422, "VALIDATION_FAILED", "enabled and 1 to 500 listing_ids are required");
    }
    return tx.write(
        () -> {
          int changed = 0;
          // Stable order prevents deadlocks between overlapping bulk requests and the push worker.
          for (UUID id :
              ids.stream()
                  .distinct()
                  .sorted(java.util.Comparator.comparing(UUID::toString))
                  .toList()) {
            var row = listings.findByIdForUpdate(id).orElseThrow(ListingApiException::notFound);
            if (accountId != null && !accountId.equals(row.channelAccountId()))
              throw ListingApiException.notFound();
            if (enabled && (row.skuId() == null || row.removedAt() != null))
              throw new ListingApiException(
                  422, "LISTING_NOT_CONTROLLABLE", "Listing must be mapped and not removed");
            if (row.stockControl() == enabled) continue;
            jdbc.update(
                "UPDATE channel_listing SET stock_control = ?, updated_at = now() WHERE id = ?",
                enabled,
                id);
            jdbc.update(
                """
          INSERT INTO stock_push_pending (tenant_id, channel_account_id, listing_id, dirty_since, changed_at, mapping_revision)
          VALUES (?, ?, ?, clock_timestamp(), clock_timestamp(), 1)
          ON CONFLICT (tenant_id, listing_id) DO UPDATE
          SET dirty_since = coalesce(stock_push_pending.dirty_since, EXCLUDED.dirty_since),
              changed_at = coalesce(stock_push_pending.changed_at, EXCLUDED.changed_at),
              mapping_revision = stock_push_pending.mapping_revision + 1
          """,
                actor.tenantId(),
                row.channelAccountId(),
                id);
            audit.stockControl(actor, id, row.stockControl(), enabled);
            changed++;
          }
          return new ChannelListingViews.StockControlResult(changed);
        });
  }
}
