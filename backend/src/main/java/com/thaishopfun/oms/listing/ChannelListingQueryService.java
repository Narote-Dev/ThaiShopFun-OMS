package com.thaishopfun.oms.listing;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
class ChannelListingQueryService {

  private final ChannelListingRepository listings;
  private final ListingTransactions tx;
  private final JdbcTemplate jdbc;

  ChannelListingQueryService(
      ChannelListingRepository listings, ListingTransactions tx, JdbcTemplate jdbc) {
    this.listings = listings;
    this.tx = tx;
    this.jdbc = jdbc;
  }

  ChannelListingViews.Page list(
      UUID channelAccountId,
      Boolean mapped,
      Boolean removedOnly,
      String q,
      Integer limit,
      Integer offset,
      Boolean stockControl) {
    int pageLimit = limit == null ? 50 : Math.min(Math.max(limit, 1), 200);
    int pageOffset = offset == null ? 0 : Math.max(offset, 0);
    return tx.read(
        () -> {
          long total = listings.count(channelAccountId, mapped, removedOnly, q, stockControl);
          var items =
              listings
                  .list(
                      channelAccountId, mapped, removedOnly, q, pageLimit, pageOffset, stockControl)
                  .stream()
                  .map(ChannelListingViews::from)
                  .toList();
          String mode =
              jdbc.query(
                  "SELECT mode FROM channel_account WHERE id = ?",
                  rs -> rs.next() ? rs.getString(1) : null,
                  channelAccountId);
          double coverage =
              jdbc.queryForObject(
                  "SELECT coalesce(100.0 * count(*) FILTER (WHERE stock_control) / nullif(count(*), 0), 0) FROM channel_listing WHERE channel_account_id = ? AND sku_id IS NOT NULL AND removed_at IS NULL",
                  Double.class,
                  channelAccountId);
          return new ChannelListingViews.Page(items, total, pageLimit, pageOffset, mode, coverage);
        });
  }

  ChannelListingViews.ListingView get(UUID id) {
    return tx.read(
        () ->
            ChannelListingViews.from(
                listings.findById(id).orElseThrow(ListingApiException::notFound)));
  }
}
