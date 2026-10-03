package com.thaishopfun.oms.order;

/** When OMS controls stock for order intake (matches {@code CheckoutReserveService} planning). */
public final class OrderStockEnforcement {

  private OrderStockEnforcement() {}

  public static boolean enforced(String mode, String connectionStatus) {
    if ("DISCONNECTED".equals(connectionStatus)) {
      return false;
    }
    return "ACTIVE".equals(mode) || "SHADOW".equals(mode) || "CONTROL".equals(mode);
  }
}
