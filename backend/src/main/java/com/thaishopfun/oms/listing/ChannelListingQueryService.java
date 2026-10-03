package com.thaishopfun.oms.listing;

import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
class ChannelListingQueryService {

  private final ChannelListingRepository listings;
  private final ListingTransactions tx;

  ChannelListingQueryService(ChannelListingRepository listings, ListingTransactions tx) {
    this.listings = listings;
    this.tx = tx;
  }

  ChannelListingViews.Page list(
      UUID channelAccountId, Boolean mapped, String q, Integer limit, Integer offset) {
    int pageLimit = limit == null ? 50 : Math.min(Math.max(limit, 1), 200);
    int pageOffset = offset == null ? 0 : Math.max(offset, 0);
    return tx.read(
        () -> {
          long total = listings.count(channelAccountId, mapped, q);
          var items =
              listings.list(channelAccountId, mapped, q, pageLimit, pageOffset).stream()
                  .map(ChannelListingViews::from)
                  .toList();
          return new ChannelListingViews.Page(items, total, pageLimit, pageOffset);
        });
  }

  ChannelListingViews.ListingView get(UUID id) {
    return tx.read(
        () ->
            ChannelListingViews.from(
                listings.findById(id).orElseThrow(ListingApiException::notFound)));
  }
}
