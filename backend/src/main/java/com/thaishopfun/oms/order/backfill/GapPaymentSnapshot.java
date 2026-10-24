package com.thaishopfun.oms.order.backfill;

import com.thaishopfun.oms.channel.api.PaymentStatus;

/** REST payment status interpretation for gap refetch. */
final class GapPaymentSnapshot {

  private GapPaymentSnapshot() {}

  static boolean indicatesPaidHappened(PaymentStatus payment) {
    if (payment == null || payment.status() == null) {
      return false;
    }
    return switch (payment.status()) {
      case "PAID", "PARTIALLY_REFUNDED", "REFUNDED" -> true;
      default -> false;
    };
  }
}
