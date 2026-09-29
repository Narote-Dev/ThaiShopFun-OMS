package com.thaishopfun.oms.inbox;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/** {@code membership.changed} data. Invalid input is not retried. */
final class MembershipPayload {

  private static final Set<String> STATUSES = Set.of("ACTIVE", "GRACE", "SUSPENDED");

  final String name;
  final String tier;
  final String status;
  final long entVer;
  final Instant expiresAt;

  private MembershipPayload(
      String name, String tier, String status, long entVer, Instant expiresAt) {
    this.name = name;
    this.tier = tier;
    this.status = status;
    this.entVer = entVer;
    this.expiresAt = expiresAt;
  }

  static MembershipPayload parse(JsonNode data) {
    // Step 1: Require the entitlement fields. Do not echo the payload in the error.
    if (data == null || !data.isObject()) {
      throw new NonRetryableInboxException("membership.changed is invalid");
    }
    String tier = text(data, "tier", 100);
    String status = text(data, "status", 20);
    Long entVer = JsonLongs.exactNonNegative(data.get("ent_ver"));
    if (tier == null || status == null || entVer == null || !STATUSES.contains(status)) {
      throw new NonRetryableInboxException("membership.changed is invalid");
    }
    String name = text(data, "name", 200);
    return new MembershipPayload(name, tier, status, entVer, expiry(data));
  }

  boolean active(Clock clock) {
    // Step 1: A passed expiry is not an active entitlement, even when status still says ACTIVE.
    if (expiresAt != null && expiresAt.isBefore(clock.instant())) {
      return false;
    }
    return "ACTIVE".equals(status) || "GRACE".equals(status);
  }

  private static String text(JsonNode data, String field, int max) {
    JsonNode value = data.get(field);
    if (value == null || value.isNull()) {
      return null;
    }
    if (!value.isString()) {
      throw new NonRetryableInboxException("membership.changed is invalid");
    }
    String text = value.asString();
    if (text.isBlank()) {
      return null;
    }
    if (text.length() > max) {
      throw new NonRetryableInboxException("membership.changed is invalid");
    }
    return text;
  }

  private static Instant expiry(JsonNode data) {
    JsonNode value = data.get("expires_at");
    if (value == null || value.isNull()) {
      return null;
    }
    if (!value.isString()) {
      throw new NonRetryableInboxException("membership.changed is invalid");
    }
    try {
      return Instant.parse(value.asString());
    } catch (DateTimeException ex) {
      throw new NonRetryableInboxException("membership.changed is invalid");
    }
  }
}
