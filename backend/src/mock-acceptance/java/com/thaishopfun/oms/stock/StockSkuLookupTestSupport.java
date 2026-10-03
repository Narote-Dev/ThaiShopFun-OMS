package com.thaishopfun.oms.stock;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/** Test-only hook for acceptance tests that need a SKU absent from catalog lookups. */
public final class StockSkuLookupTestSupport {

  private static final AtomicReference<UUID> OMIT = new AtomicReference<>();

  private StockSkuLookupTestSupport() {}

  public static void omitSkuFromCatalogLookup(UUID skuId) {
    OMIT.set(skuId);
  }

  public static void clearOmitSku() {
    OMIT.set(null);
  }

  static UUID omittedSkuId() {
    return OMIT.get();
  }
}
