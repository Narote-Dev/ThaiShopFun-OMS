package com.thaishopfun.oms.listing;

import com.thaishopfun.oms.channel.Channel;
import com.thaishopfun.oms.channel.ChannelAccountRef;
import com.thaishopfun.oms.channel.ChannelAdapter;
import com.thaishopfun.oms.channel.ChannelAdapterRegistry;
import com.thaishopfun.oms.channel.api.ListingPage;
import com.thaishopfun.oms.channel.exception.UnsupportedCapabilityException;
import com.thaishopfun.oms.order.hold.ListingHoldHooks;
import com.thaishopfun.oms.tenant.TenantContext;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class ChannelListingSyncService {

  private record AccountRow(ChannelAccountRef ref, String channel, String status) {}

  public record SyncResult(int upserted, int removed, int mappingChanges) {}

  private final ChannelListingAccess access;
  private final ListingTransactions tx;
  private final ChannelListingRepository listings;
  private final ChannelAdapterRegistry adapters;
  private final ListingHoldHooks holdHooks;
  private final JdbcTemplate jdbc;

  public ChannelListingSyncService(
      ChannelListingAccess access,
      ListingTransactions tx,
      ChannelListingRepository listings,
      ChannelAdapterRegistry adapters,
      ListingHoldHooks holdHooks,
      JdbcTemplate jdbc) {
    this.access = access;
    this.tx = tx;
    this.listings = listings;
    this.adapters = adapters;
    this.holdHooks = holdHooks;
    this.jdbc = jdbc;
  }

  public SyncResult sync(UUID channelAccountId) {
    access.requireWriter();
    AccountRow account =
        tx.read(
            () ->
                loadAccount(channelAccountId)
                    .orElseThrow(ListingApiException::notFound));
    if ("DISCONNECTED".equals(account.status())) {
      throw ListingApiException.disconnected();
    }
    ChannelAdapter adapter = adapters.optional(Channel.valueOf(account.channel()));
    if (adapter == null || !adapter.capabilities().supportsStockPush()) {
      throw ListingApiException.capabilityUnsupported("Listing sync is not supported");
    }
    List<ListingPage.Listing> fetched = new ArrayList<>();
    String cursor = null;
    do {
      ListingPage page = adapter.listListings(account.ref(), cursor);
      fetched.addAll(page.listings());
      cursor = page.nextCursor();
    } while (cursor != null && !cursor.isBlank());
    return tx.write(
        () -> {
          UUID tenantId = TenantContext.requireTenantId();
          int upserted = 0;
          int mappingChanges = 0;
          List<String> mappedSkus = new ArrayList<>();
          for (ListingPage.Listing listing : fetched) {
            ChannelListingRepository.UpsertResult result =
                listings.upsertFromChannel(
                    tenantId,
                    channelAccountId,
                    listing.listingSkuId(),
                    listing.sellerSku(),
                    listing.name(),
                    listing.available(),
                    listing.stockVersion());
            upserted++;
            if (result.mappingChanged()) {
              mappingChanges++;
              mappedSkus.add(listing.listingSkuId());
            }
          }
          jdbc.update(
              "UPDATE channel_account SET last_synced_at = now() WHERE id = ?",
              channelAccountId);
          for (String externalSkuId : mappedSkus) {
            holdHooks.afterMappingCommit(channelAccountId, externalSkuId);
          }
          return new SyncResult(upserted, 0, mappingChanges);
        });
  }

  private java.util.Optional<AccountRow> loadAccount(UUID channelAccountId) {
    UUID tenantId = TenantContext.requireTenantId();
    return jdbc
        .query(
            """
            SELECT id, channel, external_shop_id, status
            FROM channel_account WHERE id = ? AND tenant_id = ?
            """,
            (rs, row) ->
                new AccountRow(
                    new ChannelAccountRef(
                        tenantId,
                        rs.getObject("id", UUID.class),
                        rs.getString("external_shop_id")),
                    rs.getString("channel"),
                    rs.getString("status")),
            channelAccountId,
            tenantId)
        .stream()
        .findFirst();
  }
}
