package com.thaishopfun.oms.stock;

/** A reservation owner: a TSF checkout id or an OMS order id. */
public record StockOwner(OwnerType type, String ref) {

  public StockOwner {
    if (type == null) {
      throw new IllegalArgumentException("owner type is required");
    }
    if (ref == null || ref.isBlank()) {
      throw new IllegalArgumentException("owner ref is required");
    }
  }

  public static StockOwner checkout(String checkoutId) {
    return new StockOwner(OwnerType.CHECKOUT, checkoutId);
  }

  public static StockOwner order(String orderRef) {
    return new StockOwner(OwnerType.ORDER, orderRef);
  }
}
