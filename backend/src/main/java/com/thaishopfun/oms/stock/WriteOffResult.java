package com.thaishopfun.oms.stock;

import java.util.UUID;

/** Outcome of {@link StockMovements#writeOffReturn}: one {@code DAMAGE_WRITE_OFF} ledger row. */
public record WriteOffResult(
    UUID returnLineId, UUID ledgerId, UUID skuId, UUID warehouseId, int qty) {}
