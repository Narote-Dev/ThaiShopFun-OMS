package com.thaishopfun.oms.order.payment;

import java.math.BigDecimal;
import java.util.List;

/** TSF is authoritative for payments; refunds are cumulative, never added twice. */
public final class PaymentProjection {
  private PaymentProjection() {}

  public static String status(
      String snapshot,
      String current,
      BigDecimal total,
      BigDecimal snapshotRefunded,
      BigDecimal succeededRefunds) {
    if ("REFUNDED".equals(current) || "REFUNDED".equals(snapshot)) return "REFUNDED";
    BigDecimal refunded = snapshotRefunded.max(succeededRefunds);
    if (refunded.signum() > 0) {
      return total.signum() > 0 && refunded.compareTo(total) >= 0
          ? "REFUNDED"
          : "PARTIALLY_REFUNDED";
    }
    if (snapshot == null) return current;
    if ("PARTIALLY_REFUNDED".equals(current) && !"REFUNDED".equals(snapshot)) return current;
    return switch (snapshot) {
      case "PAID", "PARTIALLY_REFUNDED", "REFUNDED" -> snapshot;
      default -> List.of("PAID", "COD_PENDING").contains(current) ? current : "PENDING";
    };
  }
}
