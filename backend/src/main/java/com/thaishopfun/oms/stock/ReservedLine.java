package com.thaishopfun.oms.stock;

import java.util.UUID;

/** One {@code stock_reservation} row (a component SKU, never a bundle). */
public record ReservedLine(UUID reservationId, UUID skuId, UUID warehouseId, int qty) {}
