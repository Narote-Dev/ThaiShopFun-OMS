package com.thaishopfun.oms.order;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * Single entry point for the three order status dimensions plus {@code hold_reason}.
 *
 * <p>When {@code stockEnforced} is false (OBSERVE or DISCONNECTED channel modes), {@code
 * READY_TO_PICK} does <strong>not</strong> require an ACTIVE ORDER reservation. COD and paid orders
 * can reach {@code READY_TO_PICK} on payment and hold guards alone because OMS does not control
 * stock in those modes.
 */
@Service
public class OrderStateMachine {

  public record GuardContext(
      boolean stockEnforced, boolean reservationCoversMappedLines, Instant now) {}

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

  private static final Set<String> TERMINAL_FULFILLMENT = Set.of("SHIPPED", "DELIVERED");

  private final SalesOrderRepository orders;
  private final OrderStatusHistoryRepository history;

  public OrderStateMachine(SalesOrderRepository orders, OrderStatusHistoryRepository history) {
    this.orders = orders;
    this.history = history;
  }

  public TransitionResult applyOrderStatus(
      SalesOrder order, String to, String reason, String actor, GuardContext guards) {
    requireAllowed("ORDER", to);
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
    return persist(order, "PAYMENT", order.paymentStatus(), to, reason, actor, null, paidAt, null);
  }

  public TransitionResult applyFulfillmentStatus(
      SalesOrder order, String to, String reason, String actor, GuardContext guards) {
    requireAllowed("FULFILLMENT", to);
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
    requireAllowed("HOLD", to);
    if (!Objects.equals(to, order.holdReason())) {
      return persist(order, "HOLD", order.holdReason(), to, reason, actor, holdNote, null, null);
    }
    if (!Objects.equals(holdNote, order.holdNote())) {
      return updateHoldNoteOnly(order, holdNote);
    }
    return new TransitionResult(order, false);
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
    String nextHoldNote = holdNote == null ? order.holdNote() : holdNote;
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

  private static void guardReadyToPick(SalesOrder order, GuardContext guards) {
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

  private static void guardCompleted(SalesOrder order, GuardContext guards) {
    if (!"DELIVERED".equals(order.fulfillmentStatus())) {
      throw new OrderStateException("COMPLETED requires DELIVERED fulfillment");
    }
    if (order.paidAt() == null) {
      throw new OrderStateException("COMPLETED requires paid_at");
    }
    Instant cutoff = guards.now().minusSeconds(7L * 24 * 3600);
    if (order.paidAt().isAfter(cutoff)) {
      throw new OrderStateException("COMPLETED requires 7 days after delivery");
    }
  }

  /** Enumerated transitions for T13 matrix tests. */
  public static List<String> allowedValues(String dimension) {
    return ALLOWED.getOrDefault(dimension, Set.of()).stream().sorted().toList();
  }
}
