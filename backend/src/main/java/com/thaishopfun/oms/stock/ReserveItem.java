package com.thaishopfun.oms.stock;

import java.util.UUID;

/**
 * One requested line. {@code skuId} may be a bundle. A null {@code warehouseId} means the tenant's
 * default warehouse.
 */
public record ReserveItem(UUID skuId, int qty, UUID warehouseId) {

  public ReserveItem {
    if (skuId == null) {
      throw new IllegalArgumentException("skuId is required");
    }
    if (qty < 1) {
      throw new IllegalArgumentException("qty must be positive");
    }
  }

  public static ReserveItem of(UUID skuId, int qty) {
    return new ReserveItem(skuId, qty, null);
  }
}
