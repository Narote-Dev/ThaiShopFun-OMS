package com.thaishopfun.oms.outbox;

import java.util.UUID;

public record OutboxRetryResponse(UUID id, String status, int attempts) {}
