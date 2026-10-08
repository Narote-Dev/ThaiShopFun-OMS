package com.thaishopfun.oms.inbox;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Inbox row has a version gap; apply must use REST snapshot outside the claim transaction. */
public final class InboxGapRefetchRequired extends RuntimeException {

  private final UUID inboxId;
  private final UUID tenantId;
  private final String shopId;
  private final String externalOrderId;
  private final long aggregateVersion;
  private final OffsetDateTime leaseUntil;

  public InboxGapRefetchRequired(
      UUID inboxId,
      UUID tenantId,
      String shopId,
      String externalOrderId,
      long aggregateVersion,
      OffsetDateTime leaseUntil) {
    super("gap refetch required for order " + externalOrderId);
    this.inboxId = inboxId;
    this.tenantId = tenantId;
    this.shopId = shopId;
    this.externalOrderId = externalOrderId;
    this.aggregateVersion = aggregateVersion;
    this.leaseUntil = leaseUntil;
  }

  public UUID inboxId() {
    return inboxId;
  }

  public UUID tenantId() {
    return tenantId;
  }

  public String shopId() {
    return shopId;
  }

  public String externalOrderId() {
    return externalOrderId;
  }

  public long aggregateVersion() {
    return aggregateVersion;
  }

  public OffsetDateTime leaseUntil() {
    return leaseUntil;
  }
}
