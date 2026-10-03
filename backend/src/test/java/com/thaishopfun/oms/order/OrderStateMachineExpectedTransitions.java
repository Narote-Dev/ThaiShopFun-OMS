package com.thaishopfun.oms.order;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Expected transition edges transcribed from docs/plan/01-process-map.md (Order State). */
final class OrderStateMachineExpectedTransitions {

  private OrderStateMachineExpectedTransitions() {}

  static Map<String, Map<String, Set<String>>> expectedMatrix() {
    Map<String, Set<String>> order = Map.of("ACTIVE", Set.of("CANCELLED", "COMPLETED"));
    Map<String, Set<String>> payment =
        Map.of(
            "PENDING", Set.of("PAID"),
            "COD_PENDING", Set.of("PAID"),
            "PAID", Set.of("PARTIALLY_REFUNDED", "REFUNDED"),
            "PARTIALLY_REFUNDED", Set.of("REFUNDED"));
    Map<String, Set<String>> fulfillment =
        Map.of(
            "UNFULFILLED", Set.of("READY_TO_PICK"),
            "READY_TO_PICK", Set.of("PICKING"),
            "PICKING", Set.of("PACKED"),
            "PACKED", Set.of("READY_TO_PICK", "SHIPPED"),
            "SHIPPED", Set.of("DELIVERED"));
    Set<String> holdValues =
        Set.of(
            "NONE",
            "SKU_NOT_MAPPED",
            "OUT_OF_STOCK",
            "ADDRESS_PROBLEM",
            "PAYMENT_MISMATCH",
            "CHANNEL_CANCEL_PENDING",
            "MANUAL");
    Map<String, Set<String>> hold = new HashMap<>();
    for (String from : holdValues) {
      hold.put(from, new HashSet<>(holdValues));
    }
    Map<String, Map<String, Set<String>>> table = new HashMap<>();
    table.put("ORDER", order);
    table.put("PAYMENT", payment);
    table.put("FULFILLMENT", fulfillment);
    table.put("HOLD", hold);
    return table;
  }

  static boolean isLegalEdge(String dimension, String from, String to) {
    if (from.equals(to)) {
      return true;
    }
    Map<String, Set<String>> edges = expectedMatrix().get(dimension);
    if (edges == null) {
      return false;
    }
    Set<String> targets = edges.get(from);
    return targets != null && targets.contains(to);
  }
}
