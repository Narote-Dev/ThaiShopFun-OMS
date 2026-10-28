package com.thaishopfun.oms.stockdrift;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.thaishopfun.oms.channel.*;
import com.thaishopfun.oms.channel.api.ListingPage;
import com.thaishopfun.oms.stock.StockFixture;
import com.thaishopfun.oms.stock.StockTestBase;
import com.thaishopfun.oms.stockpush.StockPushService;
import com.thaishopfun.oms.tenant.TenantContext;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Real Postgres, FORCE RLS, T15 availability/versioning/outbox, and atomic issue/audit writes. */
class StockDriftAcceptanceTest extends StockTestBase {
  @Autowired StockDriftService drift;
  @Autowired StockPushService pushes;
  @Autowired JsonMapper json;
  @MockitoBean ChannelAdapterRegistry adapters;

  private ChannelAdapter adapter;

  private UUID setup(StockFixture.Shop shop, String mode, boolean controlled, String external) {
    UUID account = fixture.tsfChannelAccount(shop, mode, "CONNECTED");
    UUID sku = fixture.sku(shop, 10);
    fixture.channelListing(shop, account, external, sku, controlled);
    pushes.flush(shop.tenant(), account, true);
    settled(shop);
    if (adapter == null) {
      adapter = mock(ChannelAdapter.class);
      when(adapters.require(Channel.TSF)).thenReturn(adapter);
    }
    return account;
  }

  private void observed(ListingPage.Listing... rows) {
    doAnswer(
            call -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              assertThat(TenantContext.requireTenantId())
                  .isEqualTo(call.<ChannelAccountRef>getArgument(0).tenantId());
              return new ListingPage(List.of(rows), null);
            })
        .when(adapter)
        .listListings(any(), any());
  }

  private ListingPage.Listing row(String id, int qty) {
    return new ListingPage.Listing(id, "SKU", "สินค้า", qty, 1);
  }

  private void settled(StockFixture.Shop shop) {
    fixture.runInTenant(
        shop.tenant(),
        () ->
            jdbc.update(
                """
        UPDATE outbox_event SET status = 'SENT', sent_at = clock_timestamp() - interval '1 second'
        WHERE event_type = 'stock.updated'
        """));
  }

  private long count(StockFixture.Shop shop, String sql) {
    return fixture.inTenant(shop.tenant(), () -> jdbc.queryForObject(sql, Long.class));
  }

  private JsonNode details(StockFixture.Shop shop) {
    return fixture.inTenant(
        shop.tenant(),
        () ->
            json.readTree(
                jdbc.queryForObject(
                    "SELECT details::text FROM reconciliation_issue WHERE rule = 'STOCK_DRIFT' AND status <> 'RESOLVED'",
                    String.class)));
  }

  @Test
  void aggregatesTwoListingsRepushesAtomicallyKeepsAckAndResolvesWithoutChangingStock() {
    var shop = fixture.shop("ACTIVE");
    UUID account = setup(shop, "ACTIVE", false, "L1");
    UUID otherSku = fixture.sku(shop, 10);
    fixture.channelListing(shop, account, "L2", otherSku, false);
    pushes.flush(shop.tenant(), account, true);
    settled(shop);
    long onHand = count(shop, "SELECT sum(on_hand) FROM inventory");
    long ledger = count(shop, "SELECT count(*) FROM inventory_ledger");
    observed(row("L1", 2), row("L2", 3));
    var result = drift.run(shop.tenant());
    assertThat(result.drifting()).isEqualTo(2);
    assertThat(result.repush_events()).isEqualTo(1);
    assertThat(
            count(
                shop,
                "SELECT count(*) FROM outbox_event WHERE status = 'PENDING' AND event_type = 'stock.updated'"))
        .isEqualTo(1);
    assertThat(details(shop).path("listings").size()).isEqualTo(2);
    String firstSeen = details(shop).path("listings").get(0).path("first_seen_at").asString();
    assertThat(
            count(
                shop, "SELECT count(*) FROM channel_listing WHERE last_seen_channel_qty IN (2,3)"))
        .isEqualTo(2);
    assertThat(count(shop, "SELECT count(*) FROM audit_log WHERE action = 'STOCK_DRIFT_RUN'"))
        .isEqualTo(1);
    // Pending repairs remain visible and do not enqueue again.
    assertThat(drift.run(shop.tenant()).repush_events()).isZero();
    assertThat(count(shop, "SELECT count(*) FROM reconciliation_issue")).isEqualTo(1);
    settled(shop);
    fixture.runInTenant(
        shop.tenant(), () -> jdbc.update("UPDATE reconciliation_issue SET status = 'ACK'"));
    assertThat(drift.run(shop.tenant()).repush_events()).isEqualTo(1);
    assertThat(details(shop).path("listings").get(0).path("first_seen_at").asString())
        .isEqualTo(firstSeen);
    assertThat(count(shop, "SELECT count(*) FROM reconciliation_issue WHERE status = 'ACK'"))
        .isEqualTo(1);
    settled(shop);
    observed(row("L1", 10), row("L2", 10));
    assertThat(drift.run(shop.tenant()).drifting()).isZero();
    assertThat(count(shop, "SELECT count(*) FROM reconciliation_issue WHERE status = 'RESOLVED'"))
        .isEqualTo(1);
    assertThat(count(shop, "SELECT sum(on_hand) FROM inventory")).isEqualTo(onHand);
    assertThat(count(shop, "SELECT count(*) FROM inventory_ledger")).isEqualTo(ledger);
  }

  @Test
  void ignoresNonControlledAndPausedListingsButRecordsObservations() {
    for (String mode : List.of("OBSERVE", "SHADOW", "CONTROL", "ACTIVE")) {
      var shop = fixture.shop("ACTIVE");
      UUID account = setup(shop, mode, false, "L");
      fixture.runInTenant(
          shop.tenant(),
          () -> {
            jdbc.update("UPDATE stock_push_pending SET dirty_since = NULL");
            jdbc.update("UPDATE channel_listing SET last_exposed_qty = 10");
            if (mode.equals("ACTIVE"))
              jdbc.update(
                  "UPDATE channel_account SET stock_sync_paused = true WHERE id = ?", account);
          });
      observed(row("L", 3));
      assertThat(drift.run(shop.tenant()).drifting()).isZero();
      assertThat(count(shop, "SELECT count(*) FROM reconciliation_issue")).isZero();
      assertThat(
              count(shop, "SELECT count(*) FROM channel_listing WHERE last_seen_channel_qty = 3"))
          .isEqualTo(1);
    }
  }

  @Test
  void controlledAllowlistAndListingSpecificPendingAndSentDuringFetchAreRespected() {
    var shop = fixture.shop("ACTIVE");
    UUID account = setup(shop, "CONTROL", true, "L1");
    UUID sku = fixture.sku(shop, 10);
    fixture.channelListing(shop, account, "L2", sku, true);
    pushes.flush(shop.tenant(), account, true);
    // Only L2 remains in flight. L1 can still be checked and repaired.
    fixture.runInTenant(
        shop.tenant(),
        () ->
            jdbc.update(
                """
        UPDATE outbox_event SET status = CASE WHEN payload->'data'->'items' @> '[{"listing_sku_id":"L2"}]'::jsonb
          THEN 'IN_FLIGHT' ELSE 'SENT' END,
          sent_at = clock_timestamp() - interval '1 second'
        """));
    observed(row("L1", 4), row("L2", 4));
    assertThat(drift.run(shop.tenant()).drifting()).isEqualTo(1);
    settled(shop);
    doAnswer(
            call -> {
              // A delivery completed after this scan started: its observation is stale.
              fixture.runInTenant(
                  shop.tenant(),
                  () -> jdbc.update("UPDATE outbox_event SET sent_at = clock_timestamp()"));
              return new ListingPage(List.of(row("L1", 4), row("L2", 4)), null);
            })
        .when(adapter)
        .listListings(any(), any());
    assertThat(drift.run(shop.tenant()).repush_events()).isZero();
    assertThat(details(shop).path("listings").size()).isEqualTo(1);
  }

  @Test
  void fullPagingFailurePreservesIssueAndObservationsAndTenantsAreIsolated() {
    var shop = fixture.shop("ACTIVE");
    setup(shop, "ACTIVE", true, "L1");
    observed(row("L1", 3));
    drift.run(shop.tenant());
    settled(shop);
    JsonNode before = details(shop);
    doReturn(new ListingPage(List.of(row("L1", 10)), "next"))
        .when(adapter)
        .listListings(any(), isNull());
    doThrow(new IllegalStateException("TSF unavailable"))
        .when(adapter)
        .listListings(any(), eq("next"));
    assertThatThrownBy(() -> drift.run(shop.tenant())).isInstanceOf(IllegalStateException.class);
    assertThat(details(shop)).isEqualTo(before);
    assertThat(count(shop, "SELECT last_seen_channel_qty FROM channel_listing")).isEqualTo(3);
    var other = fixture.shop("ACTIVE");
    assertThat(as(other, () -> drift.issues(null))).isEmpty();
  }

  @Test
  void pagesEveryConnectedAccountAndKeepsExternalIdsScopedToTheirAccount() {
    var shop = fixture.shop("ACTIVE");
    UUID first = setup(shop, "ACTIVE", true, "shared");
    UUID sku = fixture.sku(shop, 10);
    fixture.channelListing(shop, first, "page2-listing", sku, true);
    UUID second =
        fixture.channelAccount(shop, "other-shop-" + UUID.randomUUID(), "ACTIVE", "CONNECTED");
    fixture.channelListing(shop, second, "shared", sku, true);
    UUID disconnected =
        fixture.channelAccount(shop, "disconnected-" + UUID.randomUUID(), "ACTIVE", "DISCONNECTED");
    pushes.flush(shop.tenant(), first, true);
    pushes.flush(shop.tenant(), second, true);
    settled(shop);
    doAnswer(
            call -> {
              ChannelAccountRef account = call.getArgument(0);
              String cursor = call.getArgument(1);
              assertThat(account.channelAccountId()).isNotEqualTo(disconnected);
              if (account.channelAccountId().equals(second))
                return new ListingPage(List.of(row("shared", 5)), null);
              return cursor == null
                  ? new ListingPage(List.of(row("shared", 3)), "next")
                  : new ListingPage(List.of(row("page2-listing", 4)), null);
            })
        .when(adapter)
        .listListings(any(), any());
    var result = drift.run(shop.tenant());
    assertThat(result.drifting()).isEqualTo(3);
    assertThat(result.repush_events()).isEqualTo(2);
    assertThat(count(shop, "SELECT count(*) FROM reconciliation_issue")).isEqualTo(1);
    assertThat(details(shop).path("listings").size()).isEqualTo(3);
    verify(adapter, times(3)).listListings(any(), any());
  }

  @Test
  void dirtyMarkersSkipComparisonAndMissingChannelRowsDoNotResolveKnownDrift() {
    var shop = fixture.shop("ACTIVE");
    setup(shop, "ACTIVE", true, "L");
    fixture.runInTenant(
        shop.tenant(),
        () -> jdbc.update("UPDATE stock_push_pending SET dirty_since = clock_timestamp()"));
    observed(row("L", 3));
    assertThat(drift.run(shop.tenant()).drifting()).isZero();
    fixture.runInTenant(
        shop.tenant(), () -> jdbc.update("UPDATE stock_push_pending SET dirty_since = NULL"));
    assertThat(drift.run(shop.tenant()).drifting()).isEqualTo(1);
    settled(shop);
    observed();
    assertThat(drift.run(shop.tenant()).drifting()).isEqualTo(1);
    assertThat(count(shop, "SELECT count(*) FROM reconciliation_issue WHERE status = 'OPEN'"))
        .isEqualTo(1);
    // Leaving the controlled set removes the entry even if TSF no longer reports the listing.
    fixture.runInTenant(
        shop.tenant(), () -> jdbc.update("UPDATE channel_account SET status = 'DISCONNECTED'"));
    assertThat(drift.run(shop.tenant()).drifting()).isZero();
    assertThat(count(shop, "SELECT count(*) FROM reconciliation_issue WHERE status = 'RESOLVED'"))
        .isEqualTo(1);
  }

  @Test
  void simultaneousScansCreateOneAggregateAndOneRepair() throws Exception {
    var shop = fixture.shop("ACTIVE");
    setup(shop, "ACTIVE", true, "L");
    var fetched = new CyclicBarrier(2);
    doAnswer(
            call -> {
              fetched.await(10, TimeUnit.SECONDS);
              return new ListingPage(List.of(row("L", 1)), null);
            })
        .when(adapter)
        .listListings(any(), any());
    ExecutorService workers = Executors.newFixedThreadPool(2);
    try {
      Future<StockDriftService.Result> first = workers.submit(() -> drift.run(shop.tenant()));
      Future<StockDriftService.Result> second = workers.submit(() -> drift.run(shop.tenant()));
      assertThat(
              first.get(20, TimeUnit.SECONDS).repush_events()
                  + second.get(20, TimeUnit.SECONDS).repush_events())
          .isEqualTo(1);
    } finally {
      workers.shutdownNow();
    }
    assertThat(count(shop, "SELECT count(*) FROM reconciliation_issue WHERE status <> 'RESOLVED'"))
        .isEqualTo(1);
  }

  @Test
  void repushFailureRollsBackIssueObservationAndAuditTogether() {
    var shop = fixture.shop("ACTIVE");
    setup(shop, "ACTIVE", true, "L");
    observed(row("L", 2));
    // Version overflow in the real T15 builder rolls back the entire drift transaction.
    fixture.runInTenant(
        shop.tenant(),
        () -> jdbc.update("UPDATE channel_listing SET last_pushed_version = ?", Long.MAX_VALUE));
    assertThatThrownBy(() -> drift.run(shop.tenant())).isInstanceOf(ArithmeticException.class);
    assertThat(count(shop, "SELECT count(*) FROM reconciliation_issue")).isZero();
    assertThat(
            count(
                shop,
                "SELECT count(*) FROM channel_listing WHERE last_seen_channel_qty IS NOT NULL"))
        .isZero();
    assertThat(count(shop, "SELECT count(*) FROM outbox_event WHERE status = 'PENDING'")).isZero();
    assertThat(count(shop, "SELECT count(*) FROM audit_log WHERE action = 'STOCK_DRIFT_RUN'"))
        .isZero();
  }
}
