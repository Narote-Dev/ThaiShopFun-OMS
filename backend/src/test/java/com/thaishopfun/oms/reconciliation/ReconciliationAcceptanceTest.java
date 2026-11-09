package com.thaishopfun.oms.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

import com.thaishopfun.oms.auth.UuidV7;
import com.thaishopfun.oms.channel.*;
import com.thaishopfun.oms.channel.api.ListingPage;
import com.thaishopfun.oms.invariant.SkipInvariantCheck;
import com.thaishopfun.oms.stock.StockFixture;
import com.thaishopfun.oms.stock.StockTestBase;
import com.thaishopfun.oms.stockpush.StockPushService;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SkipInvariantCheck("Reconciliation seeds edge orders and deliberate ledger drift")
class ReconciliationAcceptanceTest extends StockTestBase {

  @Autowired ReconciliationService reconciliation;
  @Autowired StockPushService pushes;
  @MockitoBean ChannelAdapterRegistry adapters;

  private ChannelAdapter adapter;

  UUID seedOrder(
      StockFixture.Shop shop,
      UUID account,
      String external,
      String orderStatus,
      String payment,
      String fulfillment,
      Instant shipBy,
      Instant deliveredAt) {
    UUID id = UuidV7.generate();
    fixture.runInTenant(
        shop.tenant(),
        () -> {
          jdbc.update(
              """
              INSERT INTO sales_order (
                id, tenant_id, channel_account_id, external_order_id, order_status, payment_status,
                fulfillment_status, hold_reason, payment_method, currency, subtotal, shipping_fee,
                discount, grand_total, ordered_at, updated_at, ship_by, version
              ) VALUES (?, ?, ?, ?, ?, ?, ?, 'NONE', 'PREPAID', 'THB', 100, 0, 0, 100, now(), now(), ?, 0)
              """,
              id,
              shop.tenant(),
              account,
              external,
              orderStatus,
              payment,
              fulfillment,
              shipBy == null ? null : Timestamp.from(shipBy));
          if (deliveredAt != null) {
            UUID warehouse = shop.warehouse();
            jdbc.update(
                """
                INSERT INTO shipment (
                  id, tenant_id, order_id, warehouse_id, status, delivered_at
                ) VALUES (?, ?, ?, ?, 'DELIVERED', ?)
                """,
                UuidV7.generate(),
                shop.tenant(),
                id,
                warehouse,
                Timestamp.from(deliveredAt));
          }
        });
    return id;
  }

  void snapshot(StockFixture.Shop shop, UUID orderId, BigDecimal amount) {
    fixture.runInTenant(
        shop.tenant(),
        () ->
            jdbc.update(
                """
                INSERT INTO payment_status_snapshot (
                  id, tenant_id, order_id, provider, provider_ref, status, amount, refunded_amount,
                  currency, observed_at, source_event_id
                ) VALUES (?, ?, ?, 'XENDIT', 'ref', 'PAID', ?, 0, 'THB', now(), ?)
                """,
                UuidV7.generate(),
                shop.tenant(),
                orderId,
                amount,
                "evt-" + UuidV7.generate()));
  }

  long openIssues(StockFixture.Shop shop, String rule) {
    return fixture.inTenant(
        shop.tenant(),
        () ->
            jdbc.queryForObject(
                "SELECT count(*) FROM reconciliation_issue WHERE rule = ? AND status <> 'RESOLVED'",
                Long.class,
                rule));
  }

  @Test
  void eachRuleIsDetectedAndRerunStaysIdempotent() {
    var shop = fixture.shop("ACTIVE");
    UUID account = fixture.tsfChannelAccount(shop, "ACTIVE", "CONNECTED");
    Instant past = Instant.now().minus(2, ChronoUnit.DAYS);
    Instant oldDelivered = Instant.now().minus(20, ChronoUnit.DAYS);
    Instant oldReceived = Instant.now().minus(10, ChronoUnit.DAYS);

    seedOrder(shop, account, "LATE", "ACTIVE", "PAID", "PACKED", past, null);
    seedOrder(shop, account, "UNPAID", "ACTIVE", "PENDING", "SHIPPED", null, null);
    seedOrder(shop, account, "PAID-CANCEL", "CANCELLED", "PAID", "UNFULFILLED", null, null);
    seedOrder(shop, account, "COD", "ACTIVE", "COD_PENDING", "DELIVERED", null, oldDelivered);

    UUID refundOrder =
        seedOrder(shop, account, "RETURN", "ACTIVE", "PAID", "DELIVERED", null, null);
    UUID returnId = UuidV7.generate();
    fixture.runInTenant(
        shop.tenant(),
        () -> {
          jdbc.update(
              """
              INSERT INTO return_request (
                id, tenant_id, order_id, external_return_id, type, status, received_at
              ) VALUES (?, ?, ?, 'R1', 'RETURN', 'RECEIVED', ?)
              """,
              returnId,
              shop.tenant(),
              refundOrder,
              Timestamp.from(oldReceived));
        });

    UUID mismatch = seedOrder(shop, account, "PAY", "ACTIVE", "PAID", "DELIVERED", null, null);
    snapshot(shop, mismatch, new BigDecimal("50"));

    if (adapter == null) {
      adapter = org.mockito.Mockito.mock(ChannelAdapter.class);
      when(adapters.require(Channel.TSF)).thenReturn(adapter);
    }
    UUID sku = fixture.sku(shop, 5);
    fixture.channelListing(shop, account, "L-RC", sku, true);
    pushes.flush(shop.tenant(), account, true);
    fixture.runInTenant(
        shop.tenant(),
        () ->
            jdbc.update(
                """
                UPDATE outbox_event SET status = 'SENT', sent_at = clock_timestamp() - interval '1 second'
                WHERE event_type = 'stock.updated'
                """));
    doAnswer(
            call ->
                new ListingPage(
                    List.of(new ListingPage.Listing("L-RC", "SKU", "สินค้า", 1, 1)), null))
        .when(adapter)
        .listListings(any(), any());

    fixture.runInTenant(
        shop.tenant(),
        () ->
            jdbc.update(
                """
                UPDATE inventory i SET on_hand = on_hand + 3, stock_version = stock_version + 1
                FROM sku s WHERE s.id = i.sku_id AND s.tenant_id = i.tenant_id AND s.tenant_id = ?
                """,
                shop.tenant()));

    var first = reconciliation.run(shop.tenant(), "MANUAL");
    assertThat(first.by_rule().get(ReconciliationRules.SHIP_BY_LATE)).isEqualTo(1);
    assertThat(first.by_rule().get(ReconciliationRules.SHIPPED_UNPAID)).isEqualTo(1);
    assertThat(first.by_rule().get(ReconciliationRules.PAID_AFTER_CANCEL)).isEqualTo(1);
    assertThat(first.by_rule().get(ReconciliationRules.COD_COLLECTION_OVERDUE)).isEqualTo(1);
    assertThat(first.by_rule().get(ReconciliationRules.REFUND_MISSING_AFTER_RETURN)).isEqualTo(1);
    assertThat(first.by_rule().get(ReconciliationRules.PAYMENT_AMOUNT_MISMATCH)).isEqualTo(1);
    assertThat(first.by_rule().get(ReconciliationRules.STOCK_DRIFT)).isGreaterThanOrEqualTo(1);
    assertThat(first.by_rule().get(ReconciliationRules.INVARIANT_VIOLATION)).isEqualTo(1);

    for (String rule : ReconciliationRules.ALL) {
      long count = openIssues(shop, rule);
      assertThat(count).as(rule).isGreaterThanOrEqualTo(1);
    }

    Map<String, Long> openBefore = new HashMap<>();
    for (String rule : ReconciliationRules.ALL) {
      openBefore.put(rule, openIssues(shop, rule));
    }
    var second = reconciliation.run(shop.tenant(), "MANUAL");
    for (String rule : ReconciliationRules.ALL) {
      assertThat(openIssues(shop, rule)).as("idempotent " + rule).isEqualTo(openBefore.get(rule));
      assertThat(second.by_rule().get(rule)).isEqualTo(first.by_rule().get(rule));
    }
    assertThat(
            fixture.inTenant(
                shop.tenant(),
                () ->
                    jdbc.queryForObject(
                        """
                        SELECT count(*) FROM reconciliation_issue
                        WHERE rule = ? AND order_id = (
                          SELECT id FROM sales_order WHERE external_order_id = 'LATE'
                        )
                        """,
                        Long.class,
                        ReconciliationRules.SHIP_BY_LATE)))
        .isEqualTo(1);
    UUID lateIssueRun =
        fixture.inTenant(
            shop.tenant(),
            () ->
                jdbc.queryForObject(
                    """
                    SELECT run_id FROM reconciliation_issue
                    WHERE rule = ? AND order_id = (
                      SELECT id FROM sales_order WHERE external_order_id = 'LATE'
                    )
                    """,
                    UUID.class,
                    ReconciliationRules.SHIP_BY_LATE));
    assertThat(lateIssueRun).isEqualTo(second.run_id());
  }
}
