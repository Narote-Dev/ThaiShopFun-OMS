package com.thaishopfun.oms.order.payment;

import com.thaishopfun.oms.inbox.NonRetryableInboxException;

/** Resolve only the explicit provider reported by TSF; never infer it from provider_ref. */
public final class PaymentProvider {
  private PaymentProvider() {}

  public static String resolve(String provider, String providerRef) {
    if (provider != null) {
      if ("XENDIT".equals(provider) || "OPN".equals(provider)) return provider;
      throw new NonRetryableInboxException("invalid payment provider");
    }
    return null;
  }
}
