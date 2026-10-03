package com.thaishopfun.oms.listing;

import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/channel-accounts")
class ChannelAccountListingSyncController {

  private final ChannelListingSyncService sync;

  ChannelAccountListingSyncController(ChannelListingSyncService sync) {
    this.sync = sync;
  }

  @PostMapping("/{id}/listing-syncs")
  ResponseEntity<ChannelListingViews.SyncResponse> sync(@PathVariable UUID id) {
    ChannelListingSyncService.SyncResult result = sync.sync(id);
    return ResponseEntity.accepted()
        .cacheControl(CacheControl.noStore())
        .body(
            new ChannelListingViews.SyncResponse(
                result.upserted(), result.removed(), result.mappingChanges()));
  }
}
