package com.thaishopfun.oms.order.backfill;

/** REST snapshot is behind the inbox aggregate_version; gap row must stay retryable. */
public class GapSnapshotNotReadyException extends RuntimeException {

  private final long snapshotVersion;
  private final long requiredVersion;

  public GapSnapshotNotReadyException(long snapshotVersion, long requiredVersion) {
    super(
        "REST aggregate_version "
            + snapshotVersion
            + " is behind inbox aggregate_version "
            + requiredVersion);
    this.snapshotVersion = snapshotVersion;
    this.requiredVersion = requiredVersion;
  }

  public long snapshotVersion() {
    return snapshotVersion;
  }

  public long requiredVersion() {
    return requiredVersion;
  }
}
