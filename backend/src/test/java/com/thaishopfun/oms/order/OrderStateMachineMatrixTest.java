package com.thaishopfun.oms.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
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
    orders = mock(SalesOrderRepository.class);
    history = mock(OrderStatusHistoryRepository.class);
    machine = new OrderStateMachine(orders, history);
  }

  @org.junit.jupiter.api.AfterEach
  void resetMocks() {
    Mockito.reset(orders, history);
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
          if (from.equals(to)) {
            continue;
          }
          boolean legal = OrderStateMachineExpectedTransitions.isLegalEdge(dimension, from, to);
          SalesOrder order = baseOrderForDimensionTest(dimension, from);
          if ("ORDER".equals(dimension) && "COMPLETED".equals(to)) {
            order = withDimension(order, "FULFILLMENT", "DELIVERED");
            when(history.transitionedAt(order.id(), "FULFILLMENT", "DELIVERED"))
                .thenReturn(Optional.of(NOW.minus(8, ChronoUnit.DAYS)));
          }
          final SalesOrder orderForApply = order;
          if (legal) {
            stubSuccessfulUpdate(orderForApply, dimension, to);
            apply(dimension, orderForApply, to, guard(false, true, false));
          } else {
            assertThatThrownBy(() -> apply(dimension, orderForApply, to, guard(false, true, false)))
                .isInstanceOf(OrderStateException.class);
          }
        }
      }
    }
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

  @Test
  void readyToPickGuardOracleAcrossFullStateSpace() {
    List<String> orderStatuses = OrderStateMachine.allowedValues("ORDER");
    List<String> payments = OrderStateMachine.allowedValues("PAYMENT");
    List<String> fulfillments = OrderStateMachine.allowedValues("FULFILLMENT");
    List<String> holds = OrderStateMachine.allowedValues("HOLD");
    boolean[][] stockFlags = {{true, true}, {true, false}, {false, true}, {false, false}};
    for (String orderStatus : orderStatuses) {
      for (String payment : payments) {
        for (String fulfillment : fulfillments) {
          if ("READY_TO_PICK".equals(fulfillment)) {
            continue;
          }
          for (String hold : holds) {
            for (boolean[] flags : stockFlags) {
              Mockito.reset(orders, history);
              SalesOrder order = sampleOrder(orderStatus, payment, fulfillment, hold);
              GuardContext guards = guard(flags[0], flags[1], false);
              boolean expectOk = oracleReadyToPick(order, guards);
              if (expectOk) {
                stubSuccessfulUpdate(order, "FULFILLMENT", "READY_TO_PICK");
              }
              if ("CANCELLED".equals(orderStatus)) {
                assertThatThrownBy(
                        () ->
                            machine.applyFulfillmentStatus(
                                order, "READY_TO_PICK", "t", "A", guards))
                    .isInstanceOf(OrderStateException.class);
                continue;
              }
              if (expectOk) {
                stubSuccessfulUpdate(order, "FULFILLMENT", "READY_TO_PICK");
                machine.applyFulfillmentStatus(order, "READY_TO_PICK", "t", "A", guards);
              } else {
                assertThatThrownBy(
                        () ->
                            machine.applyFulfillmentStatus(
                                order, "READY_TO_PICK", "t", "A", guards))
                    .isInstanceOfAny(OrderStateException.class, OrderOptimisticLockException.class);
              }
            }
          }
        }
      }
    }
  }

  @Test
  void holdBlocksAnyFulfillmentAdvanceAcrossStateSpace() {
    for (String hold : OrderStateMachine.allowedValues("HOLD")) {
      if ("NONE".equals(hold)) {
        continue;
      }
      for (String fulfillment : OrderStateMachine.allowedValues("FULFILLMENT")) {
        String target =
            OrderStateMachine.transitionTable()
                .get("FULFILLMENT")
                .getOrDefault(fulfillment, Set.of())
                .stream()
                .filter(t -> !t.equals(fulfillment))
                .findFirst()
                .orElse(null);
        if (target == null) {
          continue;
        }
        SalesOrder order = sampleOrder("ACTIVE", "PAID", fulfillment, hold);
        GuardContext guards = guard(false, true, false);
        assertThatThrownBy(() -> machine.applyFulfillmentStatus(order, target, "t", "A", guards))
            .isInstanceOf(OrderStateException.class)
            .hasMessageContaining("hold blocks");
      }
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
    GuardContext tooSoon = guard(true, true, false);
    assertThatThrownBy(
            () -> machine.applyOrderStatus(completedOrder, "COMPLETED", "t", "A", tooSoon))
        .hasMessageContaining("7 days");

    when(history.transitionedAt(completedOrder.id(), "FULFILLMENT", "DELIVERED"))
        .thenReturn(Optional.of(NOW.minus(7, ChronoUnit.DAYS)));
    stubSuccessfulUpdate(completedOrder, "ORDER", "COMPLETED");
    machine.applyOrderStatus(completedOrder, "COMPLETED", "t", "A", guard(true, true, false));
  }

  @Test
  void forbiddenTransitionDoesNotTouchRepositories() {
    SalesOrder order = sampleOrder("ACTIVE", "PAID", "UNFULFILLED", "NONE");
    assertThatThrownBy(
            () ->
                machine.applyPaymentStatus(
                    order, "PENDING", "t", "A", null, guard(true, true, false)))
        .isInstanceOf(OrderStateException.class);
    verify(orders, never())
        .updateStatusFields(
            any(), anyLong(), anyString(), anyString(), anyString(), anyString(), any(), any());
    verify(history, never())
        .append(any(), anyString(), anyString(), anyString(), anyString(), anyString());
  }

  @Test
  void terminalOrderStatusesRejectFurtherOrderChangesWithoutDbWrites() {
    for (String terminal : List.of("CANCELLED", "COMPLETED")) {
      SalesOrder order = sampleOrder(terminal, "PAID", "UNFULFILLED", "NONE");
      Mockito.clearInvocations(orders, history);
      assertThatThrownBy(
              () -> machine.applyOrderStatus(order, "ACTIVE", "t", "A", guard(true, true, false)))
          .isInstanceOf(OrderStateException.class);
      verify(orders, never())
          .updateStatusFields(
              any(), anyLong(), anyString(), anyString(), anyString(), anyString(), any(), any());
      verify(history, never())
          .append(any(), anyString(), anyString(), anyString(), anyString(), anyString());
    }
  }

  @Test
  void terminalPaymentRefundedRejectsPaymentTransitionsWithoutDbWrites() {
    SalesOrder order = sampleOrder("ACTIVE", "REFUNDED", "DELIVERED", "NONE");
    assertThatThrownBy(
            () ->
                machine.applyPaymentStatus(order, "PAID", "t", "A", NOW, guard(true, true, false)))
        .isInstanceOf(OrderStateException.class);
    verify(orders, never())
        .updateStatusFields(
            any(), anyLong(), anyString(), anyString(), anyString(), anyString(), any(), any());
  }

  private static boolean oracleReadyToPick(SalesOrder order, GuardContext guards) {
    if (!"ACTIVE".equals(order.orderStatus())) {
      return false;
    }
    if (!Set.of("PAID", "COD_PENDING").contains(order.paymentStatus())) {
      return false;
    }
    if (!"NONE".equals(order.holdReason())) {
      return false;
    }
    if (!"UNFULFILLED".equals(order.fulfillmentStatus())) {
      return false;
    }
    if (guards.stockEnforced() && !guards.reservationCoversMappedLines()) {
      return false;
    }
    return OrderStateMachineExpectedTransitions.isLegalEdge(
        "FULFILLMENT", order.fulfillmentStatus(), "READY_TO_PICK");
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
