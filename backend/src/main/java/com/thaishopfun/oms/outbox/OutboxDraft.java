package com.thaishopfun.oms.outbox;

import java.time.Instant;

/** One event written in the caller's business transaction. {@code occurredAt} null means now. */
public record OutboxDraft(
    String aggregateType,
    String aggregateId,
    String eventType,
    Object data,
    int schemaVersion,
    long aggregateVersion,
    Instant occurredAt) {

  public static OutboxDraft of(
      String aggregateType, String aggregateId, String eventType, Object data) {
    return new OutboxDraft(aggregateType, aggregateId, eventType, data, 1, 1, null);
  }
}
