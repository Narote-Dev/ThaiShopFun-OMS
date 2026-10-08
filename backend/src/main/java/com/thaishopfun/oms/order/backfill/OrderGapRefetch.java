package com.thaishopfun.oms.order.backfill;

import java.util.UUID;

/** Applies a TSF REST order snapshot when inbox aggregate_version has a gap. */
public interface OrderGapRefetch {

  void refetchAndApply(
      UUID tenantId,
      String shopId,
      String externalOrderId,
      long inboxAggregateVersion,
      String prefix);
}
