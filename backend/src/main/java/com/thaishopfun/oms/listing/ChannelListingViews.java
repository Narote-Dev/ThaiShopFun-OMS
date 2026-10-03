package com.thaishopfun.oms.listing;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

final class ChannelListingViews {

  private ChannelListingViews() {}

  record Page(List<ListingView> items, long total, int limit, int offset) {}

  record ListingView(
      UUID id,
      @JsonProperty("channel_account_id") UUID channelAccountId,
      @JsonProperty("external_sku_id") String externalSkuId,
      @JsonProperty("seller_sku") String sellerSku,
      String name,
      @JsonProperty("sku_id") UUID skuId,
      @JsonProperty("mapping_source") String mappingSource,
      @JsonProperty("mapped_at") Instant mappedAt,
      @JsonProperty("stock_control") boolean stockControl,
      @JsonProperty("held_orders") long heldOrders) {}

  record MappingBody(@JsonProperty("sku_id") UUID skuId) {}

  record ReevalSummary(
      int released,
      @JsonProperty("out_of_stock") int outOfStock,
      @JsonProperty("still_held") int stillHeld,
      int deferred) {}

  record MappingPutResponse(ListingView listing, ReevalSummary reevaluation) {}

  record SyncResponse(int upserted, int removed, @JsonProperty("mapping_changes") int mappingChanges) {}

  static ListingView from(ChannelListingRepository.ListingRow row) {
    return new ListingView(
        row.id(),
        row.channelAccountId(),
        row.externalSkuId(),
        row.sellerSku(),
        row.name(),
        row.skuId(),
        row.mappingSource(),
        row.mappedAt(),
        row.stockControl(),
        row.heldOrders());
  }
}
