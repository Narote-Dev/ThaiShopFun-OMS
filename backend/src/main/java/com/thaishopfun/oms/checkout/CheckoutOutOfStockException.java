package com.thaishopfun.oms.checkout;

import java.util.List;

/** All-or-nothing reserve refused (section 4.3 {@code OUT_OF_STOCK}). */
public final class CheckoutOutOfStockException extends RuntimeException {

  private final List<ItemConflict> items;

  CheckoutOutOfStockException(List<ItemConflict> items) {
    super("OUT_OF_STOCK");
    this.items = List.copyOf(items);
  }

  List<ItemConflict> items() {
    return items;
  }

  record ItemConflict(String listingSkuId, int requested, int available) {}
}
