package com.thaishopfun.oms.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.thaishopfun.oms.auth.UuidV7;
import com.thaishopfun.oms.order.OrderStateMachine.GuardContext;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class OrderStateMachineMatrixTest {

  private SalesOrderRepository orders;
  private OrderStatusHistoryRepository history;
  private OrderStateMachine machine;
  private static final Instant NOW = Instant.parse("2026-10-03T12:00:00Z");

  @BeforeEach
  void setup() {
    orders = Mockito.mock(SalesOrderRepository.class);
    history = Mockito.mock(OrderStatusHistoryRepository.class);
    machine = new OrderStateMachine(orders, history);
  }

  @Test
  void transitionTableMatchesExpectedMatrixFromProcessMap() {
    Map<String, Map<String, Set<String>>> expected =
        OrderStateMachineExpectedTransitions.expectedMatrix();
    Map<String, Map<String, Set<String>>> actual = OrderStateMachine.transitionTable();
    for (String dimension : List.of("ORDER", "PAYMENT", "FULFILLMENT", "HOLD")) {
      assertThat(actual).containsKey(dimension);
      Map<String, Set<String>> expectedEdges = expected.get(dimension);
      Map<String, Set<String>> actualEdges = actual.get(dimension);
      for (String from : expectedEdges.keySet()) {
        assertThat(actualEdges).as("dimension %s from %s", dimension, from).containsKey(from);
        assertThat(actualEdges.get(from))
            .as("dimension %s edges from %s", dimension, from)
            .containsExactlyInAnyOrderElementsOf(expectedEdges.get(from));
      }
      assertThat(actualEdges.keySet())
          .as("unexpected sources in %s", dimension)
          .containsExactlyInAnyOrderElementsOf(expectedEdges.keySet());
    }
  }

  @Test
  void eachDimensionPairMatchesExpectedLegality() {
    for (String dimension : List.of("ORDER", "PAYMENT", "FULFILLMENT", "HOLD")) {
      List<String> values = OrderStateMachine.allowedValues(dimension);
      for (String from : values) {
        for (String to : values) {
          clearInvocations(orders, history);
          boolean legal = OrderStateMachineExpectedTransitions.isLegalEdge(dimension, from, to);
          SalesOrder order = baseOrderForDimensionTest(dimension, from);
          if ("ORDER".equals(dimension) && "COMPLETED".equals(to)) {
            order = withDimension(order, "FULFILLMENT", "DELIVERED");
            when(history.transitionedAt(order.id(), "FULFILLMENT", "DELIVERED"))
                .thenReturn(Optional.of(NOW.minus(8, ChronoUnit.DAYS)));
          }
          final SalesOrder orderForApply = order;
          GuardContext guards = guard(false, true, false);
          if (legal) {
            if (from.equals(to) && !"HOLD".equals(dimension)) {
              apply(dimension, orderForApply, to, guards);
              verifyNoRepositoryWrites();
              continue;
            }
            if (from.equals(to) && "HOLD".equals(dimension)) {
              machine.applyHoldReason(orderForApply, to, orderForApply.holdNote(), "t", "A");
              verifyNoRepositoryWrites();
              continue;
            }
            stubSuccessfulUpdate(orderForApply, dimension, to);
            apply(dimension, orderForApply, to, guards);
            verify(history)
                .append(
                    eq(orderForApply.id()),
                    eq(dimension),
                    eq(from),
                    eq(to),
                    anyString(),
                    anyString());
          } else {
            assertRejected(() -> apply(dimension, orderForApply, to, guards));
          }
        }
      }
    }
  }

  @Test
  void guardOracleAcrossFullStateSpaceUsesExpectedMatrixTargets() {
    List<String> orderStatuses = OrderStateMachine.allowedValues("ORDER");
    List<String> payments = OrderStateMachine.allowedValues("PAYMENT");
    List<String> fulfillments = OrderStateMachine.allowedValues("FULFILLMENT");
    List<String> holds = OrderStateMachine.allowedValues("HOLD");
    boolean[][] stockFlags = {{true, true}, {true, false}, {false, true}, {false, false}};
    for (String orderStatus : orderStatuses) {
      for (String payment : payments) {
        for (String fulfillment : fulfillments) {
          for (String hold : holds) {
            for (boolean[] flags : stockFlags) {
              for (String to : OrderStateMachine.allowedValues("FULFILLMENT")) {
                if (to.equals(fulfillment)) {
                  continue;
                }
                clearInvocations(orders, history);
                SalesOrder order = sampleOrder(orderStatus, payment, fulfillment, hold);
                GuardContext guards = guard(flags[0], flags[1], false);
                boolean expectOk = oracleFulfillmentTarget(order, fulfillment, to, guards);
                if (expectOk) {
                  stubSuccessfulUpdate(order, "FULFILLMENT", to);
                  machine.applyFulfillmentStatus(order, to, "t", "A", guards);
                  verify(history)
                      .append(
                          eq(order.id()),
                          eq("FULFILLMENT"),
                          eq(fulfillment),
                          eq(to),
                          anyString(),
                          anyString());
                } else {
                  assertRejected(() -> machine.applyFulfillmentStatus(order, to, "t", "A", guards));
                }
              }
            }
          }
        }
      }
    }
  }

  @Test
  void cancelAfterShippedGuardAcrossFulfillmentStates() {
    for (String fulfillment : List.of("SHIPPED", "DELIVERED")) {
      SalesOrder order = sampleOrder("ACTIVE", "PAID", fulfillment, "NONE");
      assertRejected(
          () -> machine.applyOrderStatus(order, "CANCELLED", "t", "A", guard(true, true, false)));
    }
  }

  @Test
  void completedGuardRespectsSevenDayBoundary() {
    SalesOrder order =
        copy(
            sampleOrder("ACTIVE", "PAID", "DELIVERED", "NONE"),
            "ACTIVE",
            "PAID",
            "DELIVERED",
            "NONE",
            1L);
    order =
        new SalesOrder(
            order.id(),
            order.tenantId(),
            order.channelAccountId(),
            order.externalOrderId(),
            order.orderStatus(),
            order.paymentStatus(),
            order.fulfillmentStatus(),
            order.holdReason(),
            order.holdNote(),
            order.channelStatus(),
            order.paymentMethod(),
            order.currency(),
            order.subtotal(),
            order.shippingFee(),
            order.discount(),
            order.grandTotal(),
            order.orderedAt(),
            NOW.minus(30, ChronoUnit.DAYS),
            order.shipBy(),
            order.externalVersion(),
            order.version());
    when(history.transitionedAt(order.id(), "FULFILLMENT", "DELIVERED"))
        .thenReturn(Optional.of(NOW.minus(7, ChronoUnit.DAYS).plus(1, ChronoUnit.SECONDS)));
    final SalesOrder completedOrder = order;
    assertRejected(
        () ->
            machine.applyOrderStatus(
                completedOrder, "COMPLETED", "t", "A", guard(true, true, false)));

    when(history.transitionedAt(completedOrder.id(), "FULFILLMENT", "DELIVERED"))
        .thenReturn(Optional.of(NOW.minus(7, ChronoUnit.DAYS)));
    stubSuccessfulUpdate(completedOrder, "ORDER", "COMPLETED");
    machine.applyOrderStatus(completedOrder, "COMPLETED", "t", "A", guard(true, true, false));
  }

  @Test
  void cancelledAndCompletedOrdersAllowRefundPaymentTransitions() {
    SalesOrder cancelled = sampleOrder("CANCELLED", "PAID", "DELIVERED", "NONE");
    stubSuccessfulUpdate(cancelled, "PAYMENT", "REFUNDED");
    machine.applyPaymentStatus(cancelled, "REFUNDED", "refund", "A", NOW, guard(true, true, false));
    verify(history)
        .append(
            eq(cancelled.id()),
            eq("PAYMENT"),
            eq("PAID"),
            eq("REFUNDED"),
            anyString(),
            anyString());

    SalesOrder completed = sampleOrder("COMPLETED", "PAID", "DELIVERED", "NONE");
    stubSuccessfulUpdate(completed, "PAYMENT", "PARTIALLY_REFUNDED");
    machine.applyPaymentStatus(
        completed, "PARTIALLY_REFUNDED", "refund", "A", NOW, guard(true, true, false));
  }

  @Test
  void cancelledAndCompletedOrdersRejectFulfillmentAndHoldWithoutDbWrites() {
    SalesOrder cancelled = sampleOrder("CANCELLED", "PAID", "UNFULFILLED", "NONE");
    assertRejected(
        () ->
            machine.applyFulfillmentStatus(
                cancelled, "READY_TO_PICK", "t", "A", guard(false, true, false)));
    assertRejected(() -> machine.applyHoldReason(cancelled, "MANUAL", "n", "t", "A"));

    SalesOrder completed = sampleOrder("COMPLETED", "PAID", "DELIVERED", "NONE");
    assertRejected(
        () ->
            machine.applyFulfillmentStatus(
                completed, "SHIPPED", "t", "A", guard(false, true, false)));
    assertRejected(() -> machine.applyHoldReason(completed, "MANUAL", "n", "t", "A"));
  }

  @Test
  void terminalOrderStatusesRejectFurtherOrderChangesWithoutDbWrites() {
    for (String terminal : List.of("CANCELLED", "COMPLETED")) {
      SalesOrder order = sampleOrder(terminal, "PAID", "UNFULFILLED", "NONE");
      assertRejected(
          () -> machine.applyOrderStatus(order, "ACTIVE", "t", "A", guard(true, true, false)));
    }
  }

  @Test
  void terminalOrdersPaymentMatrixOnlyAllowsRefunds() {
    for (String orderStatus : List.of("CANCELLED", "COMPLETED")) {
      for (String from : OrderStateMachine.allowedValues("PAYMENT")) {
        for (String to : OrderStateMachine.allowedValues("PAYMENT")) {
          clearInvocations(orders, history);
          SalesOrder order = sampleOrder(orderStatus, from, "DELIVERED", "NONE");
          boolean legalEdge = OrderStateMachineExpectedTransitions.isLegalEdge("PAYMENT", from, to);
          boolean allowed = legalEdge && oracleTerminalPaymentAllowed(from, to);
          if (allowed) {
            if (from.equals(to)) {
              apply("PAYMENT", order, to, guard(true, true, false));
              verifyNoRepositoryWrites();
            } else {
              stubSuccessfulUpdate(order, "PAYMENT", to);
              apply("PAYMENT", order, to, guard(true, true, false));
              verify(history)
                  .append(
                      eq(order.id()), eq("PAYMENT"), eq(from), eq(to), anyString(), anyString());
            }
          } else {
            assertRejected(() -> apply("PAYMENT", order, to, guard(true, true, false)));
          }
        }
      }
    }
  }

  @Test
  void completedRejectsWhenOpenReturn() {
    SalesOrder order = sampleOrder("ACTIVE", "PAID", "DELIVERED", "NONE");
    when(history.transitionedAt(order.id(), "FULFILLMENT", "DELIVERED"))
        .thenReturn(Optional.of(NOW.minus(8, ChronoUnit.DAYS)));
    assertRejected(
        () -> machine.applyOrderStatus(order, "COMPLETED", "t", "A", guard(true, true, true)));
  }

  @Test
  void orderTargetGuardsAcrossPaymentFulfillmentAndHold() {
    List<String> payments = OrderStateMachine.allowedValues("PAYMENT");
    List<String> fulfillments = OrderStateMachine.allowedValues("FULFILLMENT");
    List<String> holds = OrderStateMachine.allowedValues("HOLD");
    for (String payment : payments) {
      for (String fulfillment : fulfillments) {
        for (String hold : holds) {
          SalesOrder order = sampleOrder("ACTIVE", payment, fulfillment, hold);
          for (String to : List.of("CANCELLED", "COMPLETED")) {
            clearInvocations(orders, history);
            if ("CANCELLED".equals(to)) {
              if (Set.of("SHIPPED", "DELIVERED").contains(fulfillment)) {
                assertRejected(
                    () ->
                        machine.applyOrderStatus(
                            order, "CANCELLED", "t", "A", guard(true, true, false)));
              } else {
                stubSuccessfulUpdate(order, "ORDER", "CANCELLED");
                machine.applyOrderStatus(order, "CANCELLED", "t", "A", guard(true, true, false));
              }
              continue;
            }
            boolean completedOk =
                "DELIVERED".equals(fulfillment) && "PAID".equals(payment) && order.paidAt() != null;
            if (!completedOk) {
              assertRejected(
                  () ->
                      machine.applyOrderStatus(
                          order, "COMPLETED", "t", "A", guard(true, true, false)));
              continue;
            }
            when(history.transitionedAt(order.id(), "FULFILLMENT", "DELIVERED"))
                .thenReturn(Optional.of(NOW.minus(8, ChronoUnit.DAYS)));
            stubSuccessfulUpdate(order, "ORDER", "COMPLETED");
            machine.applyOrderStatus(order, "COMPLETED", "t", "A", guard(true, true, false));
          }
        }
      }
    }
  }

  private static boolean oracleTerminalPaymentAllowed(String from, String to) {
    if (from.equals(to)) {
      return true;
    }
    return Set.of("PARTIALLY_REFUNDED", "REFUNDED").contains(to);
  }

  private static boolean oracleFulfillmentTarget(
      SalesOrder order, String fromFulfillment, String to, GuardContext guards) {
    if (!OrderStateMachineExpectedTransitions.isLegalEdge("FULFILLMENT", fromFulfillment, to)) {
      return false;
    }
    if (!order.fulfillmentStatus().equals(fromFulfillment)) {
      return false;
    }
    if ("CANCELLED".equals(order.orderStatus()) || "COMPLETED".equals(order.orderStatus())) {
      return false;
    }
    if (!"NONE".equals(order.holdReason())) {
      return false;
    }
    if ("READY_TO_PICK".equals(to)) {
      if (!"ACTIVE".equals(order.orderStatus())) {
        return false;
      }
      if (!Set.of("PAID", "COD_PENDING").contains(order.paymentStatus())) {
        return false;
      }
      if (guards.stockEnforced() && !guards.reservationCoversMappedLines()) {
        return false;
      }
    }
    return true;
  }

  private void assertRejected(Runnable action) {
    assertThatThrownBy(action::run).isInstanceOf(OrderStateException.class);
    verifyNoRepositoryWrites();
  }

  private void verifyNoRepositoryWrites() {
    verify(orders, never())
        .updateStatusFields(
            any(), anyLong(), anyString(), anyString(), anyString(), anyString(), any(), any());
    verify(history, never())
        .append(any(), anyString(), anyString(), anyString(), anyString(), anyString());
  }

  private SalesOrder baseOrderForDimensionTest(String dimension, String from) {
    SalesOrder order = sampleOrder("ACTIVE", "PAID", "UNFULFILLED", "NONE");
    order = withDimension(order, dimension, from);
    if ("ORDER".equals(dimension) && "CANCELLED".equals(from)) {
      order = withDimension(order, "PAYMENT", "PENDING");
    }
    if ("FULFILLMENT".equals(dimension) && Set.of("SHIPPED", "DELIVERED").contains(from)) {
      order = withDimension(order, "PAYMENT", "PAID");
    }
    return order;
  }

  private void apply(String dimension, SalesOrder order, String to, GuardContext guards) {
    switch (dimension) {
      case "ORDER" -> machine.applyOrderStatus(order, to, "t", "A", guards);
      case "PAYMENT" -> machine.applyPaymentStatus(order, to, "t", "A", NOW, guards);
      case "FULFILLMENT" -> machine.applyFulfillmentStatus(order, to, "t", "A", guards);
      case "HOLD" -> machine.applyHoldReason(order, to, "note", "t", "A");
      default -> throw new IllegalArgumentException(dimension);
    }
  }

  private void stubSuccessfulUpdate(SalesOrder order, String dimension, String to) {
    SalesOrder next = withDimension(order, dimension, to);
    SalesOrder updated =
        copy(
            next,
            next.orderStatus(),
            next.paymentStatus(),
            next.fulfillmentStatus(),
            next.holdReason(),
            order.version() + 1);
    when(orders.updateStatusFields(
            eq(order.id()),
            eq(order.version()),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            any(),
            any()))
        .thenReturn(Optional.of(updated));
  }

  private static SalesOrder withDimension(SalesOrder order, String dimension, String value) {
    String orderStatus = order.orderStatus();
    String payment = order.paymentStatus();
    String fulfillment = order.fulfillmentStatus();
    String hold = order.holdReason();
    switch (dimension) {
      case "ORDER" -> orderStatus = value;
      case "PAYMENT" -> payment = value;
      case "FULFILLMENT" -> fulfillment = value;
      case "HOLD" -> hold = value;
      default -> {}
    }
    return copy(order, orderStatus, payment, fulfillment, hold, order.version());
  }

  private static SalesOrder copy(
      SalesOrder order,
      String orderStatus,
      String payment,
      String fulfillment,
      String hold,
      long version) {
    return new SalesOrder(
        order.id(),
        order.tenantId(),
        order.channelAccountId(),
        order.externalOrderId(),
        orderStatus,
        payment,
        fulfillment,
        hold,
        order.holdNote(),
        order.channelStatus(),
        order.paymentMethod(),
        order.currency(),
        order.subtotal(),
        order.shippingFee(),
        order.discount(),
        order.grandTotal(),
        order.orderedAt(),
        order.paidAt(),
        order.shipBy(),
        order.externalVersion(),
        version);
  }

  private static GuardContext guard(boolean stockEnforced, boolean covers, boolean openReturn) {
    return new GuardContext(stockEnforced, covers, NOW, openReturn);
  }

  private static SalesOrder sampleOrder(
      String orderStatus, String payment, String fulfillment, String hold) {
    return new SalesOrder(
        UuidV7.generate(),
        UuidV7.generate(),
        UuidV7.generate(),
        "TSF-1",
        orderStatus,
        payment,
        fulfillment,
        hold,
        null,
        null,
        "COD",
        "THB",
        BigDecimal.TEN,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        BigDecimal.TEN,
        NOW,
        "PAID".equals(payment) ? NOW : null,
        null,
        1L,
        1L);
  }
}
