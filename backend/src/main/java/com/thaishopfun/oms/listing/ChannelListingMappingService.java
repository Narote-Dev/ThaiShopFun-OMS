package com.thaishopfun.oms.listing;

import com.thaishopfun.oms.order.hold.ListingHoldHooks;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class ChannelListingMappingService {

  private final ChannelListingAccess access;
  private final ListingTransactions tx;
  private final ChannelListingRepository listings;
  private final ListingAudit audit;
  private final ListingHoldHooks holdHooks;
  private final JdbcTemplate jdbc;

  public ChannelListingMappingService(
      ChannelListingAccess access,
      ListingTransactions tx,
      ChannelListingRepository listings,
      ListingAudit audit,
      ListingHoldHooks holdHooks,
      JdbcTemplate jdbc) {
    this.access = access;
    this.tx = tx;
    this.listings = listings;
    this.audit = audit;
    this.holdHooks = holdHooks;
    this.jdbc = jdbc;
  }

  public ChannelListingViews.ListingView putMapping(UUID listingId, UUID skuId) {
    ChannelListingAccess.Actor actor = access.requireWriter();
    return tx.write(
        () -> {
          ChannelListingRepository.ListingRow row =
              listings.findById(listingId).orElseThrow(ListingApiException::notFound);
          ensureSku(skuId);
          UUID previous = row.skuId();
          listings.putManualMapping(listingId, skuId);
          audit.mapped(actor, listingId, skuId);
          holdHooks.afterMappingCommit(row.channelAccountId(), row.externalSkuId());
          return ChannelListingViews.from(listings.findById(listingId).orElseThrow());
        });
  }

  public ChannelListingViews.ListingView deleteMapping(UUID listingId) {
    ChannelListingAccess.Actor actor = access.requireWriter();
    return tx.write(
        () -> {
          ChannelListingRepository.ListingRow row =
              listings.findById(listingId).orElseThrow(ListingApiException::notFound);
          UUID previous = row.skuId();
          listings.clearMapping(listingId);
          audit.unmapped(actor, listingId, previous);
          return ChannelListingViews.from(listings.findById(listingId).orElseThrow());
        });
  }

  private void ensureSku(UUID skuId) {
    Long count =
        jdbc.queryForObject("SELECT count(*) FROM sku WHERE id = ?", Long.class, skuId);
    if (count == null || count == 0) {
      throw new ListingApiException(404, "NOT_FOUND", "SKU not found");
    }
  }
}
