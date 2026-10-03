package com.thaishopfun.oms.order.web;

import com.thaishopfun.oms.catalog.CatalogApiException;
import java.time.Instant;
import java.util.UUID;

/** Keyset cursor with a list snapshot upper bound (ordered_at). */
final class OrderListCursor {

  private final Instant snapshotBefore;
  private final Instant orderedAt;
  private final UUID id;

  private OrderListCursor(Instant snapshotBefore, Instant orderedAt, UUID id) {
    this.snapshotBefore = snapshotBefore;
    this.orderedAt = orderedAt;
    this.id = id;
  }

  static OrderListCursor firstPage(Instant snapshotBefore) {
    return new OrderListCursor(snapshotBefore, null, null);
  }

  static OrderListCursor decode(String raw) {
    if (raw == null || raw.isBlank()) {
      throw CatalogApiException.invalid("cursor is invalid");
    }
    String[] parts = raw.split("\\|", 3);
    if (parts.length != 3) {
      throw CatalogApiException.invalid("cursor is invalid");
    }
    try {
      Instant snapshot = Instant.parse(parts[0]);
      Instant orderedAt = Instant.parse(parts[1]);
      UUID id = UUID.fromString(parts[2]);
      return new OrderListCursor(snapshot, orderedAt, id);
    } catch (RuntimeException ex) {
      throw CatalogApiException.invalid("cursor is invalid");
    }
  }

  String encode(Instant lastOrderedAt, UUID lastId) {
    return snapshotBefore + "|" + lastOrderedAt + "|" + lastId;
  }

  Instant snapshotBefore() {
    return snapshotBefore;
  }

  boolean hasKeyset() {
    return orderedAt != null && id != null;
  }

  Instant orderedAt() {
    return orderedAt;
  }

  UUID id() {
    return id;
  }
}
