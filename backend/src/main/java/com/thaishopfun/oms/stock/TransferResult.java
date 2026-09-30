package com.thaishopfun.oms.stock;

import java.util.List;
import java.util.UUID;

/** A group now owned by an ORDER. {@code reserved} and the ledger are unchanged. */
public record TransferResult(UUID reservationGroupId, StockOwner owner, List<ReservedLine> lines) {}
