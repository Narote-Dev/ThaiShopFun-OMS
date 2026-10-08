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
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class ChannelListingSyncService {

  private static final Logger log = LoggerFactory.getLogger(ChannelListingSyncService.class);

  private record AccountRow(ChannelAccountRef ref, String channel, String status) {}

  private record Written(
      int fetched,
      int created,
      int updated,
      int autoMapped,
      int revived,
      int removed,
      boolean removalSkipped,
      List<String> reevalSkus) {}

  public record SyncResult(
      int fetched,
      int created,
      int updated,
      int autoMapped,
      int revived,
      int removed,
      boolean removalSkipped,
      int reevaluatedOrders,
      int deferred) {}

  private final ChannelListingAccess access;
  private final ListingTransactions tx;
  private final ChannelListingRepository listings;
  private final ChannelAdapterRegistry adapters;
  private final OrderHoldResolverJob resolverJob;
  private final OrderHoldProperties holdProperties;
  private final ListingSyncProperties syncProperties;
  private final JdbcTemplate jdbc;
  private final Clock clock;

  public ChannelListingSyncService(
      ChannelListingAccess access,
      ListingTransactions tx,
      ChannelListingRepository listings,
      ChannelAdapterRegistry adapters,
      OrderHoldResolverJob resolverJob,
      OrderHoldProperties holdProperties,
      ListingSyncProperties syncProperties,
      JdbcTemplate jdbc,
      Clock clock) {
    this.access = access;
    this.tx = tx;
    this.listings = listings;
    this.adapters = adapters;
    this.resolverJob = resolverJob;
    this.holdProperties = holdProperties;
    this.syncProperties = syncProperties;
    this.jdbc = jdbc;
    this.clock = clock;
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
    Instant syncStarted = clock.instant();
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
    Set<String> fetchedIds = new HashSet<>();
    for (ListingPage.Listing listing : fetched) {
      fetchedIds.add(listing.listingSkuId());
    }
    Written written =
        tx.write(
            () -> {
              UUID tenantId = TenantContext.requireTenantId();
              int created = 0;
              int updated = 0;
              int autoMapped = 0;
              int revived = 0;
              List<String> reevalSkus = new ArrayList<>();
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
                if (result.revived()) {
                  revived++;
                }
                if (result.inserted()) {
                  created++;
                } else {
                  updated++;
                }
                if (result.mappingChanged() || result.revived()) {
                  reevalSkus.add(listing.listingSkuId());
                }
              }
              int removed = 0;
              boolean removalSkipped = false;
              if (!fetchedIds.isEmpty()) {
                long active = listings.countActive(channelAccountId);
                long wouldRemove =
                    listings.countVanishedCandidates(channelAccountId, fetchedIds, syncStarted);
                if (active >= syncProperties.getMinActiveForGuard()
                    && wouldRemove > active * syncProperties.getMaxRemovalRatio()) {
                  removalSkipped = true;
                } else if (wouldRemove > 0) {
                  removed = listings.markVanished(channelAccountId, fetchedIds, syncStarted);
                  if (removed > 0) {
                    log.info(
                        "listing sync marked {} vanished listings for channel_account_id={}",
                        removed,
                        channelAccountId);
                  }
                }
              }
              jdbc.update(
                  "UPDATE channel_account SET last_synced_at = now() WHERE id = ?",
                  channelAccountId);
              return new Written(
                  fetched.size(),
                  created,
                  updated,
                  autoMapped,
                  revived,
                  removed,
                  removalSkipped,
                  reevalSkus);
            });
    int reevaluated = 0;
    int deferred = 0;
    int remainingCap = holdProperties.getReevalCap();
    for (String externalSkuId : written.reevalSkus()) {
      if (remainingCap <= 0) {
        deferred +=
            tx.read(
                () -> listings.countHeldOrdersForListing(channelAccountId, externalSkuId));
        continue;
      }
      ReevalSummary summary =
          resolverJob.reevalForListing(channelAccountId, externalSkuId, Math.max(0, remainingCap));
      int processed = summary.released() + summary.outOfStock() + summary.stillHeld();
      reevaluated += processed;
      deferred += summary.deferred();
      remainingCap -= processed;
    }
    return new SyncResult(
        written.fetched(),
        written.created(),
        written.updated(),
        written.autoMapped(),
        written.revived(),
        written.removed(),
        written.removalSkipped(),
        reevaluated,
        deferred);
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
