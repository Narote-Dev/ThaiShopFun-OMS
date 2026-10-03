package com.thaishopfun.oms.order.hold;

import java.util.UUID;

/** Test seam: invoked after listing mapping commits, before hold re-evaluation. */
public interface ListingHoldHooks {

  void afterMappingCommit(UUID channelAccountId, String externalSkuId);
}
