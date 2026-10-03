package com.thaishopfun.oms.listing;

import com.thaishopfun.oms.channel.Channel;
import com.thaishopfun.oms.channel.ChannelAccountRef;
import com.thaishopfun.oms.channel.ChannelAdapter;
import com.thaishopfun.oms.channel.ChannelAdapterRegistry;
import com.thaishopfun.oms.channel.api.ListingPage;
import com.thaishopfun.oms.channel.exception.ChannelClientException;
import com.thaishopfun.oms.channel.exception.ChannelServerErrorException;
import com.thaishopfun.oms.channel.exception.ChannelUnavailableException;
import com.thaishopfun.oms.order.hold.OrderHoldProperties;
import com.thaishopfun.oms.order.hold.OrderHoldResolverJob;
import com.thaishopfun.oms.order.hold.OrderHoldResolverJob.ReevalSummary;
import com.thaishopfun.oms.tenant.TenantContext;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class ChannelListingSyncService {

  private record AccountRow(ChannelAccountRef ref, String channel, String status) {}

  public record SyncResult(
      int fetched,
      int created,
      int updated,
      int autoMapped,
      int reevaluatedOrders,
      List<String> mappedSkus) {}

  private final ChannelListingAccess access;
  private final ListingTransactions tx;
  private final ChannelListingRepository listings;
  private final ChannelAdapterRegistry adapters;
  private final OrderHoldResolverJob resolverJob;
  private final OrderHoldProperties holdProperties;
  private final JdbcTemplate jdbc;

  public ChannelListingSyncService(
      ChannelListingAccess access,
      ListingTransactions tx,
      ChannelListingRepository listings,
      ChannelAdapterRegistry adapters,
      OrderHoldResolverJob resolverJob,
      OrderHoldProperties holdProperties,
      JdbcTemplate jdbc) {
    this.access = access;
    this.tx = tx;
    this.listings = listings;
    this.adapters = adapters;
    this.resolverJob = resolverJob;
    this.holdProperties = holdProperties;
    this.jdbc = jdbc;
  }

  public SyncResult sync(UUID channelAccountId) {
    access.requireWriter();
    AccountRow account =
        tx.read(() -> loadAccount(channelAccountId).orElseThrow(ListingApiException::notFound));
    if ("DISCONNECTED".equals(account.status())) {
      throw ListingApiException.disconnected();
    }
    ChannelAdapter adapter = adapters.optional(Channel.valueOf(account.channel()));
    if (adapter == null || !adapter.capabilities().supportsListingSync()) {
      throw ListingApiException.capabilityUnsupported("Listing sync is not supported");
    }
    List<ListingPage.Listing> fetched = new ArrayList<>();
    try {
      String cursor = null;
      do {
        ListingPage page = adapter.listListings(account.ref(), cursor);
        fetched.addAll(page.listings());
        cursor = page.nextCursor();
      } while (cursor != null && !cursor.isBlank());
    } catch (ChannelServerErrorException | ChannelClientException ex) {
      throw new ListingApiException(502, "CHANNEL_ERROR", "Channel listing sync failed");
    } catch (ChannelUnavailableException ex) {
      throw new ListingApiException(
          503, "CHANNEL_UNAVAILABLE", "Channel is temporarily unavailable");
    }
    SyncResult written =
        tx.write(
            () -> {
              UUID tenantId = TenantContext.requireTenantId();
              int created = 0;
              int updated = 0;
              int autoMapped = 0;
              List<String> mappedSkus = new ArrayList<>();
              for (ListingPage.Listing listing : fetched) {
                ChannelListingRepository.UpsertResult result =
                    listings.upsertFromChannel(
                        tenantId,
                        channelAccountId,
                        listing.listingSkuId(),
                        listing.sellerSku(),
                        listing.name());
                if (result.newlyMapped()) {
                  autoMapped++;
                }
                if (result.inserted()) {
                  created++;
                } else {
                  updated++;
                }
                if (result.mappingChanged()) {
                  mappedSkus.add(listing.listingSkuId());
                }
              }
              jdbc.update(
                  "UPDATE channel_account SET last_synced_at = now() WHERE id = ?",
                  channelAccountId);
              return new SyncResult(fetched.size(), created, updated, autoMapped, 0, mappedSkus);
            });
    int reevaluated = 0;
    int remainingCap = holdProperties.getReevalCap();
    for (String externalSkuId : written.mappedSkus()) {
      ReevalSummary summary =
          resolverJob.reevalForListing(channelAccountId, externalSkuId, Math.max(0, remainingCap));
      int processed = summary.released() + summary.outOfStock() + summary.stillHeld();
      reevaluated += processed;
      remainingCap -= processed;
    }
    return new SyncResult(
        written.fetched(),
        written.created(),
        written.updated(),
        written.autoMapped(),
        reevaluated,
        written.mappedSkus());
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
                        tenantId, rs.getObject("id", UUID.class), rs.getString("external_shop_id")),
                    rs.getString("channel"),
                    rs.getString("status")),
            channelAccountId,
            tenantId)
        .stream()
        .findFirst();
  }
}
