package com.thaishopfun.oms.listing;

import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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
  private final ChannelListingStockControlService stockControl;

  ChannelListingController(
      ChannelListingQueryService queries,
      ChannelListingMappingService mapping,
      ChannelListingStockControlService stockControl) {
    this.queries = queries;
    this.mapping = mapping;
    this.stockControl = stockControl;
  }

  @GetMapping
  ResponseEntity<ChannelListingViews.Page> list(
      @RequestParam(name = "channel_account_id", required = false) String channelAccountId,
      @RequestParam(name = "mapped", required = false) Boolean mapped,
      @RequestParam(name = "removed", required = false) Boolean removedOnly,
      @RequestParam(name = "q", required = false) String q,
      @RequestParam(name = "limit", required = false) Integer limit,
      @RequestParam(name = "offset", required = false) Integer offset,
      @RequestParam(name = "stock_control", required = false) Boolean controlled) {
    UUID accountId = parseChannelAccountId(channelAccountId);
    return ok(queries.list(accountId, mapped, removedOnly, q, limit, offset, controlled));
  }

  @PutMapping("/{id}/stock-control")
  ResponseEntity<ChannelListingViews.StockControlResult> stockControl(
      @PathVariable UUID id, @RequestBody ChannelListingViews.StockControlBody body) {
    return ok(stockControl.change(null, java.util.List.of(id), body.enabled()));
  }

  @PostMapping("/stock-control")
  ResponseEntity<ChannelListingViews.StockControlResult> bulkStockControl(
      @RequestBody ChannelListingViews.BulkStockControlBody body) {
    if (body.channelAccountId() == null) throw ListingApiException.missingChannelAccountId();
    return ok(stockControl.change(body.channelAccountId(), body.listingIds(), body.enabled()));
  }

  private static UUID parseChannelAccountId(String raw) {
    if (raw == null || raw.isBlank()) {
      throw ListingApiException.missingChannelAccountId();
    }
    try {
      return UUID.fromString(raw.trim());
    } catch (IllegalArgumentException ex) {
      throw ListingApiException.invalidChannelAccountId();
    }
  }

  @GetMapping("/{id}")
  ResponseEntity<ChannelListingViews.ListingView> get(@PathVariable UUID id) {
    return ok(queries.get(id));
  }

  @PutMapping("/{id}/mapping")
  ResponseEntity<ChannelListingViews.MappingPutResponse> putMapping(
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
