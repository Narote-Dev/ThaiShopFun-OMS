package com.thaishopfun.oms.order.backfill;

import static org.assertj.core.api.Assertions.assertThat;

import com.thaishopfun.oms.channel.api.PaymentStatus;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class GapPaymentSnapshotTest {
  @ParameterizedTest
  @ValueSource(strings = {"PAID", "PARTIALLY_REFUNDED", "REFUNDED"})
  void settledAndRefundedPaymentsMeanPaidHappened(String status) {
    assertThat(
            GapPaymentSnapshot.indicatesPaidHappened(new PaymentStatus("order", status, List.of())))
        .isTrue();
  }

  @ParameterizedTest
  @ValueSource(strings = {"UNPAID", "FAILED", "PENDING"})
  void unsettledPaymentDoesNotMeanPaidHappened(String status) {
    assertThat(
            GapPaymentSnapshot.indicatesPaidHappened(new PaymentStatus("order", status, List.of())))
        .isFalse();
  }

  @Test
  void missingPaymentDoesNotMeanPaidHappened() {
    assertThat(GapPaymentSnapshot.indicatesPaidHappened(null)).isFalse();
    assertThat(
            GapPaymentSnapshot.indicatesPaidHappened(new PaymentStatus("order", null, List.of())))
        .isFalse();
  }
}
