package com.thaishopfun.oms.listing;

import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/channel-listings")
class ChannelListingController {

  private final ChannelListingQueryService queries;
  private final ChannelListingMappingService mapping;

  ChannelListingController(
      ChannelListingQueryService queries, ChannelListingMappingService mapping) {
    this.queries = queries;
    this.mapping = mapping;
  }

  @GetMapping
  ResponseEntity<ChannelListingViews.Page> list(
      @RequestParam(name = "channel_account_id") UUID channelAccountId,
      @RequestParam(name = "mapped", required = false) Boolean mapped,
      @RequestParam(name = "q", required = false) String q,
      @RequestParam(name = "limit", required = false) Integer limit,
      @RequestParam(name = "offset", required = false) Integer offset) {
    return ok(queries.list(channelAccountId, mapped, q, limit, offset));
  }

  @GetMapping("/{id}")
  ResponseEntity<ChannelListingViews.ListingView> get(@PathVariable UUID id) {
    return ok(queries.get(id));
  }

  @PutMapping("/{id}/mapping")
  ResponseEntity<ChannelListingViews.ListingView> putMapping(
      @PathVariable UUID id, @RequestBody ChannelListingViews.MappingBody body) {
    if (body == null || body.skuId() == null) {
      throw new ListingApiException(422, "VALIDATION_FAILED", "sku_id is required");
    }
    return ok(mapping.putMapping(id, body.skuId()));
  }

  @DeleteMapping("/{id}/mapping")
  ResponseEntity<ChannelListingViews.ListingView> deleteMapping(@PathVariable UUID id) {
    return ok(mapping.deleteMapping(id));
  }

  private static <T> ResponseEntity<T> ok(T body) {
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
  }
}
