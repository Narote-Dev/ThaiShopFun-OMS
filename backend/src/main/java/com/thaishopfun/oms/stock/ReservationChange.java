package com.thaishopfun.oms.stock;

import java.util.List;

/**
 * Rows moved out of ACTIVE by release, consume, or unpack. Empty for a release that found nothing
 * ACTIVE (already released, which is still a success).
 */
public record ReservationChange(List<ReservedLine> lines) {}
