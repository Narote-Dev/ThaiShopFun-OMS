package com.thaishopfun.mocktsf.rest;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class PaymentEventsTest {
  @Test
  void newlyPaidDemoOrdersHaveNoSyntheticRefund() {
    var catalog = new TsfCatalog();
    String id = catalog.createBulkOrders("shop_active", 1, "PREPAID", true).get(0);
    assertThat((List<?>) catalog.order(id).orElseThrow().payment().get("refunds")).isEmpty();
  }

  @Test
  void controlEventsUpdatePaymentGetAndDeduplicateRefundsByProviderRef() {
    var catalog = new TsfCatalog();
    var json = new JsonMapper();
    String id = catalog.createBulkOrders("shop_active", 1, "PREPAID", false).get(0);
    var paid =
        json.valueToTree(
            Map.of(
                "event_type",
                "payment.status_changed",
                "tsf_shop_id",
                "shop_active",
                "aggregate_version",
                99,
                "data",
                Map.of("order_id", id, "status", "PAID")));
    catalog.noteOrderEvent(paid);
    assertThat(catalog.order(id).orElseThrow().payment().get("status")).isEqualTo("PAID");
    var refund =
        json.valueToTree(
            Map.of(
                "event_type",
                "refund.status_changed",
                "tsf_shop_id",
                "shop_active",
                "data",
                Map.of(
                    "order_id",
                    id,
                    "refund_id",
                    "r1",
                    "status",
                    "SUCCEEDED",
                    "amount",
                    295,
                    "currency",
                    "THB")));
    catalog.noteOrderEvent(refund);
    catalog.noteOrderEvent(refund);
    var order = catalog.order(id).orElseThrow();
    assertThat((List<?>) order.payment().get("refunds")).hasSize(1);
    assertThat(order.aggregateVersion()).isEqualTo(1);
  }
}
