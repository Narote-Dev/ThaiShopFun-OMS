package com.thaishopfun.oms.stock;

import java.util.List;
import java.util.UUID;

/**
 * A component SKU that could not be reserved. {@code requested} is the summed quantity over every
 * item that needs it. {@code available} is {@code on_hand - reserved} under the lock (0 without an
 * inventory row). {@code requestedBy} lists the item SKU ids (bundle or plain) that asked for it.
 */
public record Shortfall(
    UUID skuId, UUID warehouseId, int requested, int available, List<UUID> requestedBy) {}
