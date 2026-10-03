package com.thaishopfun.oms.listing;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/channel-accounts")
class ChannelAccountListingSyncController {

  record AccountView(
      UUID id,
      String channel,
      @JsonProperty("external_shop_id") String externalShopId,
      String status) {}

  record AccountPage(List<AccountView> items) {}

  private final ChannelListingSyncService sync;
  private final ListingTransactions tx;
  private final JdbcTemplate jdbc;

  ChannelAccountListingSyncController(
      ChannelListingSyncService sync, ListingTransactions tx, JdbcTemplate jdbc) {
    this.sync = sync;
    this.tx = tx;
    this.jdbc = jdbc;
  }

  @GetMapping
  ResponseEntity<AccountPage> listAccounts() {
    List<AccountView> items =
        tx.read(
            () ->
                jdbc.query(
                    """
                    SELECT id, channel, external_shop_id, status
                    FROM channel_account
                    WHERE channel = 'TSF'
                    ORDER BY created_at, id
                    """,
                    (rs, row) ->
                        new AccountView(
                            rs.getObject("id", UUID.class),
                            rs.getString("channel"),
                            rs.getString("external_shop_id"),
                            rs.getString("status"))));
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new AccountPage(items));
  }

  @PostMapping("/{id}/listing-syncs")
  ResponseEntity<ChannelListingViews.SyncResponse> sync(@PathVariable UUID id) {
    ChannelListingSyncService.SyncResult result = sync.sync(id);
    return ResponseEntity.accepted()
        .cacheControl(CacheControl.noStore())
        .body(
            new ChannelListingViews.SyncResponse(
                result.fetched(),
                result.created(),
                result.updated(),
                result.autoMapped(),
                result.reevaluatedOrders()));
  }
}
