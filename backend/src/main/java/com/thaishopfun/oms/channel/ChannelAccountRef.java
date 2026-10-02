package com.thaishopfun.oms.channel;

import java.util.UUID;

/** Tenant-scoped channel account used for rate limiting and outbound API paths. */
public record ChannelAccountRef(UUID tenantId, UUID channelAccountId, String externalShopId) {

  public ChannelAccountRef {
    if (tenantId == null) {
      throw new IllegalArgumentException("tenantId is required");
    }
    if (channelAccountId == null) {
      throw new IllegalArgumentException("channelAccountId is required");
    }
    if (externalShopId == null || externalShopId.isBlank()) {
      throw new IllegalArgumentException("externalShopId is required");
    }
  }
}
