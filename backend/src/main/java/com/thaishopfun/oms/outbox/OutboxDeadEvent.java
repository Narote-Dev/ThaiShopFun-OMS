package com.thaishopfun.oms.outbox;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.UUID;

/** Dead-letter row returned to the admin retry screen. The payload is omitted. */
public record OutboxDeadEvent(
    UUID id,
    @JsonProperty("aggregate_type") String aggregateType,
    @JsonProperty("aggregate_id") String aggregateId,
    @JsonProperty("event_type") String eventType,
    String status,
    int attempts,
    @JsonProperty("created_at") Instant createdAt,
    @JsonProperty("next_attempt_at") Instant nextAttemptAt) {}
