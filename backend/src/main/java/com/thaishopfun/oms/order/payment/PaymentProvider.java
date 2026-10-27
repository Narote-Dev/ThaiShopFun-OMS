package com.thaishopfun.oms.order.payment;

import com.thaishopfun.oms.inbox.NonRetryableInboxException;

/** Resolve only metadata reported by TSF; never guess a provider for unpaid/COD orders. */
public final class PaymentProvider {
  private PaymentProvider() {}

  public static String resolve(String provider, String providerRef) {
    if (provider != null) {
      if ("XENDIT".equals(provider) || "OPN".equals(provider)) return provider;
      throw new NonRetryableInboxException("invalid payment provider");
    }
    if (providerRef == null) return null;
    if (providerRef.startsWith("xnd_")) return "XENDIT";
    if (providerRef.startsWith("chrg_") || providerRef.startsWith("opn")) return "OPN";
    return null;
  }
}
