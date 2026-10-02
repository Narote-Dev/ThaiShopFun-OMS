package com.thaishopfun.oms.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.thaishopfun.mocktsf.MockTsfApplication;
import com.thaishopfun.mocktsf.contract.ContractValidator;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Shared event builders for T12 mock acceptance tests. */
final class OrderIntakeScenarioSupport {

  private static final ContractValidator CONTRACT = ContractValidator.classpath();

  private OrderIntakeScenarioSupport() {}

  static ObjectNode orderCreated(
      JsonMapper json,
      String orderId,
      String shopId,
      String reservationId,
      String paymentMethod,
      String listingSku,
      int qty,
      long aggregateVersion)
      throws IOException {
    ObjectNode event = loadExample(json, "order.created.json");
    event.put("event_id", "evt-" + java.util.UUID.randomUUID());
    event.put("tsf_shop_id", shopId);
    event.put("aggregate_id", orderId);
    event.put("aggregate_version", aggregateVersion);
    event.put("occurred_at", Instant.now().truncatedTo(ChronoUnit.SECONDS).toString());
    ObjectNode data = (ObjectNode) event.get("data");
    data.put("order_id", orderId);
    data.put("reservation_id", reservationId);
    data.put("payment_method", paymentMethod);
    if ("PREPAID".equals(paymentMethod)) {
      data.put("payment_expires_at", Instant.now().plus(30, ChronoUnit.MINUTES).toString());
    }
    ArrayNode lines = json.createArrayNode();
    ObjectNode line = json.createObjectNode();
    line.put("line_id", "L1");
    line.put("listing_sku_id", listingSku);
    line.put("seller_sku", "SKU-1");
    line.put("name", "Item");
    line.put("qty", qty);
    line.put("unit_price", 100);
    lines.add(line);
    data.set("lines", lines);
    assertThat(CONTRACT.envelopeErrors(json.writeValueAsString(event))).isEmpty();
    return event;
  }

  private static ObjectNode loadExample(JsonMapper json, String name) throws IOException {
    try (InputStream in =
        MockTsfApplication.class.getResourceAsStream("/contracts/examples/events/" + name)) {
      if (in == null) {
        throw new IllegalStateException("missing example " + name);
      }
      return (ObjectNode) json.readTree(in);
    }
  }
}
