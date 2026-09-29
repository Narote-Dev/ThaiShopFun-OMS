package com.thaishopfun.oms.inbox;

import java.util.UUID;
import tools.jackson.databind.JsonNode;

/**
 * One claimed inbox row. {@code gap} is true when {@code aggregateVersion} skips ahead of the last
 * PROCESSED version. Filling that gap over REST is a later task; the handler still runs.
 */
public record InboxMessage(
    UUID id,
    UUID tenantId,
    String source,
    String eventId,
    String eventType,
    String aggregateId,
    long aggregateVersion,
    boolean gap,
    JsonNode payload) {}
