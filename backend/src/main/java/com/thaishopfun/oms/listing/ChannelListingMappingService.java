package com.thaishopfun.oms.listing;

import com.thaishopfun.oms.order.hold.OrderHoldResolverJob;
import com.thaishopfun.oms.order.hold.OrderHoldResolverJob.ReevalSummary;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class ChannelListingMappingService {

  private record WriteResult(ChannelListingRepository.ListingRow row, boolean mappingChanged) {}

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
    WriteResult written =
        tx.write(
            () -> {
              ChannelListingRepository.ListingRow row =
                  listings.findByIdForUpdate(listingId).orElseThrow(ListingApiException::notFound);
              if (skuId.equals(row.skuId())) {
                return new WriteResult(row, false);
              }
              ensureSku(skuId);
              listings.putManualMapping(listingId, skuId);
              audit.mapped(actor, listingId, skuId);
              ChannelListingRepository.ListingRow after =
                  listings.findById(listingId).orElseThrow(ListingApiException::notFound);
              return new WriteResult(after, true);
            });
    ReevalSummary summary =
        written.mappingChanged()
            ? resolverJob.reevalAfterMapping(
                written.row().channelAccountId(), written.row().externalSkuId())
            : ReevalSummary.zero();
    return new ChannelListingViews.MappingPutResponse(
        ChannelListingViews.from(written.row()), toView(summary));
  }

  public ChannelListingViews.ListingView deleteMapping(UUID listingId) {
    ChannelListingAccess.Actor actor = access.requireWriter();
    return tx.write(
        () -> {
          ChannelListingRepository.ListingRow row =
              listings.findByIdForUpdate(listingId).orElseThrow(ListingApiException::notFound);
          UUID previous = row.skuId();
          listings.clearMapping(listingId);
          if (previous != null) {
            audit.unmapped(actor, listingId, previous);
          }
          return ChannelListingViews.from(listings.findById(listingId).orElseThrow());
        });
  }

  private void ensureSku(UUID skuId) {
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
