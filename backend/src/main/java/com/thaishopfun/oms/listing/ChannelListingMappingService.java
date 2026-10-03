package com.thaishopfun.oms.listing;

import com.thaishopfun.oms.order.hold.OrderHoldResolverJob;
import com.thaishopfun.oms.order.hold.OrderHoldResolverJob.ReevalSummary;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class ChannelListingMappingService {

  private final ChannelListingAccess access;
  private final ListingTransactions tx;
  private final ChannelListingRepository listings;
  private final ListingAudit audit;
  private final OrderHoldResolverJob resolverJob;
  private final JdbcTemplate jdbc;

  public ChannelListingMappingService(
      ChannelListingAccess access,
      ListingTransactions tx,
      ChannelListingRepository listings,
      ListingAudit audit,
      OrderHoldResolverJob resolverJob,
      JdbcTemplate jdbc) {
    this.access = access;
    this.tx = tx;
    this.listings = listings;
    this.audit = audit;
    this.resolverJob = resolverJob;
    this.jdbc = jdbc;
  }

  public ChannelListingViews.MappingPutResponse putMapping(UUID listingId, UUID skuId) {
    ChannelListingAccess.Actor actor = access.requireWriter();
    ChannelListingRepository.ListingRow before =
        tx.read(() -> listings.findById(listingId).orElseThrow(ListingApiException::notFound));
    if (skuId.equals(before.skuId())) {
      return new ChannelListingViews.MappingPutResponse(
          ChannelListingViews.from(before), toView(ReevalSummary.zero()));
    }
    tx.read(
        () -> {
          ensureSku(skuId);
          return null;
        });
    UUID channelAccountId = before.channelAccountId();
    String externalSkuId = before.externalSkuId();
    tx.write(
        () -> {
          listings.putManualMapping(listingId, skuId);
          audit.mapped(actor, listingId, skuId);
          return null;
        });
    ReevalSummary summary = resolverJob.reevalAfterMapping(channelAccountId, externalSkuId);
    ChannelListingRepository.ListingRow after =
        tx.read(() -> listings.findById(listingId).orElseThrow(ListingApiException::notFound));
    return new ChannelListingViews.MappingPutResponse(
        ChannelListingViews.from(after), toView(summary));
  }

  public ChannelListingViews.ListingView deleteMapping(UUID listingId) {
    ChannelListingAccess.Actor actor = access.requireWriter();
    return tx.write(
        () -> {
          ChannelListingRepository.ListingRow row =
              listings.findById(listingId).orElseThrow(ListingApiException::notFound);
          UUID previous = row.skuId();
          listings.clearMapping(listingId);
          if (previous != null) {
            audit.unmapped(actor, listingId, previous);
          }
          return ChannelListingViews.from(listings.findById(listingId).orElseThrow());
        });
  }

  private void ensureSku(UUID skuId) {
    // Step 1: SKU lookup must run under a tenant-scoped transaction (RLS).
    Long count = jdbc.queryForObject("SELECT count(*) FROM sku WHERE id = ?", Long.class, skuId);
    if (count == null || count == 0) {
      throw new ListingApiException(404, "NOT_FOUND", "SKU not found");
    }
  }

  private static ChannelListingViews.ReevalSummary toView(ReevalSummary summary) {
    return new ChannelListingViews.ReevalSummary(
        summary.released(), summary.outOfStock(), summary.stillHeld(), summary.deferred());
  }
}
