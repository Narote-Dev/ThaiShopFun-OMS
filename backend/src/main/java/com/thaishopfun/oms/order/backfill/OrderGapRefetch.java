package com.thaishopfun.oms.order.backfill;

import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** Applies a TSF REST order snapshot when inbox aggregate_version has a gap. */
public interface OrderGapRefetch {

  /**
   * @return true when the gap event was fully applied and may be marked PROCESSED
   */
  boolean refetchAndApply(
      UUID tenantId,
      String shopId,
      String externalOrderId,
      long inboxAggregateVersion,
      String prefix,
      String inboxEventType,
      JsonNode inboxPayload);
}
