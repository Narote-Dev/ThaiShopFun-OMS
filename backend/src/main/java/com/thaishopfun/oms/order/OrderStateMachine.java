package com.thaishopfun.oms.order;

import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * Single entry point for the three order status dimensions plus {@code hold_reason}.
 *
 * <p>When {@code stockEnforced} is false (OBSERVE, DISCONNECTED, or unenforced tenant), {@code
 * READY_TO_PICK} does <strong>not</strong> require an ACTIVE ORDER reservation. COD and paid orders
 * can reach {@code READY_TO_PICK} on payment and hold guards alone (PO decision 2026-10-01).
 */
@Service
public class OrderStateMachine {

  public record GuardContext(
      boolean stockEnforced,
      boolean reservationCoversMappedLines,
      Instant now,
      boolean openReturn) {

    public GuardContext(boolean stockEnforced, boolean reservationCoversMappedLines, Instant now) {
      this(stockEnforced, reservationCoversMappedLines, now, false);
    }
  }

  public record TransitionResult(SalesOrder order, boolean fulfillmentChanged) {}

  private static final Map<String, Set<String>> ALLOWED =
      Map.of(
          "ORDER", Set.of("ACTIVE", "CANCELLED", "COMPLETED"),
          "PAYMENT", Set.of("PENDING", "PAID", "COD_PENDING", "PARTIALLY_REFUNDED", "REFUNDED"),
          "FULFILLMENT",
              Set.of("UNFULFILLED", "READY_TO_PICK", "PICKING", "PACKED", "SHIPPED", "DELIVERED"),
          "HOLD",
              Set.of(
                  "NONE",
                  "SKU_NOT_MAPPED",
                  "OUT_OF_STOCK",
                  "ADDRESS_PROBLEM",
                  "PAYMENT_MISMATCH",
                  "CHANNEL_CANCEL_PENDING",
                  "MANUAL"));

  private static final Map<String, Map<String, Set<String>>> TRANSITIONS = buildTransitions();

  private static final Set<String> TERMINAL_FULFILLMENT = Set.of("SHIPPED", "DELIVERED");

  private final SalesOrderRepository orders;
  private final OrderStatusHistoryRepository history;

  public OrderStateMachine(SalesOrderRepository orders, OrderStatusHistoryRepository history) {
    this.orders = orders;
    this.history = history;
  }

  /** Table of legal edges per dimension for T13 matrix enumeration. */
  public static Map<String, Map<String, Set<String>>> transitionTable() {
    Map<String, Map<String, Set<String>>> copy = new HashMap<>();
    TRANSITIONS.forEach(
        (dimension, edges) -> {
          Map<String, Set<String>> dimCopy = new HashMap<>();
          edges.forEach((from, targets) -> dimCopy.put(from, Set.copyOf(targets)));
          copy.put(dimension, Collections.unmodifiableMap(dimCopy));
        });
    return Collections.unmodifiableMap(copy);
  }

  public TransitionResult applyOrderStatus(
      SalesOrder order, String to, String reason, String actor, GuardContext guards) {
    requireAllowed("ORDER", to);
    requireTransition("ORDER", order.orderStatus(), to);
    if ("CANCELLED".equals(order.orderStatus()) && !order.orderStatus().equals(to)) {
      throw new OrderStateException("cancelled order is immutable");
    }
    if ("CANCELLED".equals(to) && TERMINAL_FULFILLMENT.contains(order.fulfillmentStatus())) {
      throw new OrderStateException("cannot cancel after shipped or delivered");
    }
    if ("COMPLETED".equals(to)) {
      guardCompleted(order, guards);
    }
    return persist(order, "ORDER", order.orderStatus(), to, reason, actor, null, null, null);
  }

  public TransitionResult applyPaymentStatus(
      SalesOrder order,
      String to,
      String reason,
      String actor,
      Instant paidAt,
      GuardContext guards) {
    requireAllowed("PAYMENT", to);
    requireTransition("PAYMENT", order.paymentStatus(), to);
    requireRefundOnlyPaymentOnTerminalOrder(order, to);
    return persist(order, "PAYMENT", order.paymentStatus(), to, reason, actor, null, paidAt, null);
  }

  public TransitionResult applyFulfillmentStatus(
      SalesOrder order, String to, String reason, String actor, GuardContext guards) {
    requireFulfillmentAndHoldMutable(order);
    requireAllowed("FULFILLMENT", to);
    requireTransition("FULFILLMENT", order.fulfillmentStatus(), to);
    if (!"NONE".equals(order.holdReason()) && !to.equals(order.fulfillmentStatus())) {
      throw new OrderStateException("hold blocks fulfillment changes");
    }
    if ("READY_TO_PICK".equals(to)) {
      guardReadyToPick(order, guards);
    }
    return persist(
        order, "FULFILLMENT", order.fulfillmentStatus(), to, reason, actor, null, null, null);
  }

  public TransitionResult applyHoldReason(
      SalesOrder order, String to, String holdNote, String reason, String actor) {
    requireFulfillmentAndHoldMutable(order);
    requireAllowed("HOLD", to);
    requireTransition("HOLD", order.holdReason(), to);
    if (!Objects.equals(to, order.holdReason())) {
      return persist(order, "HOLD", order.holdReason(), to, reason, actor, holdNote, null, null);
    }
    if (!Objects.equals(holdNote, order.holdNote())) {
      return updateHoldNoteOnly(order, holdNote);
    }
    return new TransitionResult(order, false);
  }

  /**
   * Fulfillment and hold are frozen on terminal order statuses; payment refunds may still apply.
   */
  private static void requireFulfillmentAndHoldMutable(SalesOrder order) {
    if ("CANCELLED".equals(order.orderStatus())) {
      throw new OrderStateException("cancelled order is immutable for fulfillment and hold");
    }
    if ("COMPLETED".equals(order.orderStatus())) {
      throw new OrderStateException("completed order is immutable for fulfillment and hold");
    }
  }

  /** On terminal orders, payment may only move into refund states (self no-op is allowed). */
  private static void requireRefundOnlyPaymentOnTerminalOrder(SalesOrder order, String to) {
    if (!"CANCELLED".equals(order.orderStatus()) && !"COMPLETED".equals(order.orderStatus())) {
      return;
    }
    if (order.paymentStatus().equals(to)) {
      return;
    }
    if (!Set.of("PARTIALLY_REFUNDED", "REFUNDED").contains(to)) {
      throw new OrderStateException(
          "cancelled or completed order is immutable for non-refund payment");
    }
  }

  private TransitionResult updateHoldNoteOnly(SalesOrder order, String holdNote) {
    var updated =
        orders.updateStatusFields(
            order.id(),
            order.version(),
            order.orderStatus(),
            order.paymentStatus(),
            order.fulfillmentStatus(),
            order.holdReason(),
            holdNote,
            order.paidAt());
    if (updated.isEmpty()) {
      throw new OrderOptimisticLockException("sales_order version conflict");
    }
    return new TransitionResult(updated.get(), false);
  }

  private TransitionResult persist(
      SalesOrder order,
      String dimension,
      String from,
      String to,
      String reason,
      String actor,
      String holdNote,
      Instant paidAt,
      String unused) {
    if (from.equals(to) && !"HOLD".equals(dimension)) {
      return new TransitionResult(order, false);
    }
    String nextHoldNote;
    if ("HOLD".equals(dimension) && "NONE".equals(to) && holdNote == null) {
      nextHoldNote = null;
    } else {
      nextHoldNote = holdNote == null ? order.holdNote() : holdNote;
    }
    Instant nextPaidAt = paidAt == null ? order.paidAt() : paidAt;
    String nextOrder = order.orderStatus();
    String nextPayment = order.paymentStatus();
    String nextFulfillment = order.fulfillmentStatus();
    String nextHold = order.holdReason();
    switch (dimension) {
      case "ORDER" -> nextOrder = to;
      case "PAYMENT" -> nextPayment = to;
      case "FULFILLMENT" -> nextFulfillment = to;
      case "HOLD" -> nextHold = to;
      default -> throw new IllegalStateException(dimension);
    }
    var updated =
        orders.updateStatusFields(
            order.id(),
            order.version(),
            nextOrder,
            nextPayment,
            nextFulfillment,
            nextHold,
            nextHoldNote,
            nextPaidAt);
    if (updated.isEmpty()) {
      throw new OrderOptimisticLockException("sales_order version conflict");
    }
    history.append(order.id(), dimension, from, to, reason, actor);
    boolean fulfillmentChanged = "FULFILLMENT".equals(dimension) && !from.equals(to);
    return new TransitionResult(updated.get(), fulfillmentChanged);
  }

  private static void requireAllowed(String dimension, String value) {
    Set<String> allowed = ALLOWED.get(dimension);
    if (allowed == null || !allowed.contains(value)) {
      throw new OrderStateException("forbidden " + dimension + " value " + value);
    }
  }

  private static void requireTransition(String dimension, String from, String to) {
    if (from.equals(to)) {
      return;
    }
    Map<String, Set<String>> edges = TRANSITIONS.get(dimension);
    if (edges == null) {
      throw new OrderStateException("unknown dimension " + dimension);
    }
    Set<String> targets = edges.get(from);
    if (targets == null || !targets.contains(to)) {
      throw new OrderStateException("illegal " + dimension + " transition " + from + " -> " + to);
    }
  }

  private static void guardReadyToPick(SalesOrder order, GuardContext guards) {
    if (!"ACTIVE".equals(order.orderStatus())) {
      throw new OrderStateException("READY_TO_PICK requires ACTIVE order");
    }
    if (!Set.of("PAID", "COD_PENDING").contains(order.paymentStatus())) {
      throw new OrderStateException("READY_TO_PICK requires PAID or COD_PENDING payment");
    }
    if (!"NONE".equals(order.holdReason())) {
      throw new OrderStateException("READY_TO_PICK requires hold NONE");
    }
    if (guards.stockEnforced() && !guards.reservationCoversMappedLines()) {
      throw new OrderStateException("READY_TO_PICK requires ORDER reservation coverage");
    }
  }

  private void guardCompleted(SalesOrder order, GuardContext guards) {
    if (!"DELIVERED".equals(order.fulfillmentStatus())) {
      throw new OrderStateException("COMPLETED requires DELIVERED fulfillment");
    }
    if (order.paidAt() == null) {
      throw new OrderStateException("COMPLETED requires paid_at");
    }
    if (guards.openReturn()) {
      throw new OrderStateException("COMPLETED requires no open return");
    }
    Instant deliveredAt =
        history
            .transitionedAt(order.id(), "FULFILLMENT", "DELIVERED")
            .orElseThrow(() -> new OrderStateException("COMPLETED requires DELIVERED history"));
    Instant cutoff = guards.now().minusSeconds(7L * 24 * 3600);
    if (deliveredAt.isAfter(cutoff)) {
      throw new OrderStateException("COMPLETED requires 7 days after delivery");
    }
  }

  private static Map<String, Map<String, Set<String>>> buildTransitions() {
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
    Set<String> holdValues = ALLOWED.get("HOLD");
    Map<String, Set<String>> hold = new HashMap<>();
    for (String from : holdValues) {
      hold.put(from, new HashSet<>(holdValues));
    }
    Map<String, Map<String, Set<String>>> table = new HashMap<>();
    table.put("ORDER", order);
    table.put("PAYMENT", payment);
    table.put("FULFILLMENT", fulfillment);
    table.put("HOLD", hold);
    return Map.copyOf(table);
  }

  /** Enumerated transitions for T13 matrix tests. */
  public static List<String> allowedValues(String dimension) {
    return ALLOWED.getOrDefault(dimension, Set.of()).stream().sorted().toList();
  }
}
