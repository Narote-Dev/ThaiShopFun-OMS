package com.thaishopfun.oms.channel.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record ListingPage(List<Listing> listings, @JsonProperty("next_cursor") String nextCursor) {

  public record Listing(
      @JsonProperty("listing_sku_id") String listingSkuId,
      @JsonProperty("seller_sku") String sellerSku,
      String name,
      int available,
      @JsonProperty("stock_version") long stockVersion) {}
}
