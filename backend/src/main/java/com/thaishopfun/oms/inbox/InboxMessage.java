package com.thaishopfun.oms.inbox;

import java.util.UUID;
import tools.jackson.databind.JsonNode;

/**
 * One claimed inbox row. {@code gap} is true when {@code aggregateVersion} skips ahead of the last
 * PROCESSED version. The worker refetches the REST snapshot instead of applying the webhook delta.
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
