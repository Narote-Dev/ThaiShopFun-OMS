package com.thaishopfun.oms.order.payment;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class PaymentProjectionTest {
  private static final BigDecimal TOTAL = new BigDecimal("580");

  private String project(String snapshot, String current, String mirrored, String refunds) {
    return PaymentProjection.status(
        snapshot, current, TOTAL, new BigDecimal(mirrored), new BigDecimal(refunds));
  }

  @Test
  void partialAndFullRefunds() {
    assertThat(project("PAID", "PAID", "0", "295")).isEqualTo("PARTIALLY_REFUNDED");
    assertThat(project("PAID", "PARTIALLY_REFUNDED", "0", "580")).isEqualTo("REFUNDED");
  }

  @Test
  void sameRefundSeenInSnapshotAndRowsIsNotAddedTwice() {
    assertThat(project("PARTIALLY_REFUNDED", "PAID", "295", "295")).isEqualTo("PARTIALLY_REFUNDED");
  }

  @Test
  void refundMayArriveBeforePayment() {
    assertThat(project(null, "PENDING", "0", "295")).isEqualTo("PARTIALLY_REFUNDED");
    assertThat(project("PENDING", "PAID", "0", "580")).isEqualTo("REFUNDED");
  }

  @Test
  void delayedPendingCannotRegressAnObservedPaymentOrFullRefund() {
    assertThat(project("PENDING", "PAID", "0", "0")).isEqualTo("PAID");
    assertThat(project("PAID", "REFUNDED", "0", "295")).isEqualTo("REFUNDED");
    assertThat(project("REFUNDED", "PAID", "0", "295")).isEqualTo("REFUNDED");
  }

  @Test
  void failedPaymentDoesNotInventAnUnsupportedOrderStatus() {
    assertThat(project("FAILED", "PENDING", "0", "0")).isEqualTo("PENDING");
    assertThat(project("UNPAID", "COD_PENDING", "0", "0")).isEqualTo("COD_PENDING");
  }
}
