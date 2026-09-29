package com.thaishopfun.oms.inbox;

import com.thaishopfun.oms.auth.UuidV7;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Applies {@code membership.changed} to {@code tenant}. A lower {@code ent_ver} is ignored. The
 * audit row stores status and {@code ent_ver} only.
 */
@Component
public class MembershipChangedHandler implements InboxHandler {

  private static final Set<String> STATUSES = Set.of("ACTIVE", "GRACE", "SUSPENDED");

  private final JdbcTemplate jdbc;

  public MembershipChangedHandler(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public String eventType() {
    return InboxEntitlementPolicy.MEMBERSHIP_CHANGED;
  }

  @Override
  public void handle(InboxMessage message) {
    // Step 1: Require the entitlement fields. Do not echo the payload in the error.
    JsonNode data = message.payload().path("data");
    String tier = text(data, "tier");
    String status = text(data, "status");
    JsonNode entVerNode = data.get("ent_ver");
    if (tier == null || status == null || entVerNode == null || !entVerNode.isIntegralNumber()) {
      throw new IllegalArgumentException("membership.changed is invalid");
    }
    long entVer = entVerNode.asLong();
    if (entVer < 0 || !STATUSES.contains(status)) {
      throw new IllegalArgumentException("membership.changed is invalid");
    }
    Instant expiresAt = expiry(data);

    // Step 2: Read the current row under this tenant's RLS context.
    Current current =
        jdbc.query(
            "SELECT entitlement_status, ent_ver FROM tenant WHERE id = ?",
            rs -> {
              if (!rs.next()) {
                throw new IllegalStateException("tenant is not visible");
              }
              return new Current(rs.getString("entitlement_status"), rs.getLong("ent_ver"));
            },
            message.tenantId());
    if (current == null) {
      throw new IllegalStateException("tenant is not visible");
    }

    // Step 3: A stale ent_ver does not overwrite a newer membership. The inbox row still completes.
    int updated =
        jdbc.update(
            """
            UPDATE tenant
            SET membership_tier = ?,
                entitlement_status = ?,
                entitlement_expires_at = ?,
                ent_ver = ?
            WHERE id = ?
              AND ent_ver <= ?
            """,
            ps -> {
              ps.setString(1, tier);
              ps.setString(2, status);
              if (expiresAt == null) {
                ps.setNull(3, Types.TIMESTAMP_WITH_TIMEZONE);
              } else {
                ps.setObject(3, OffsetDateTime.ofInstant(expiresAt, ZoneOffset.UTC));
              }
              ps.setLong(4, entVer);
              ps.setObject(5, message.tenantId());
              ps.setLong(6, entVer);
            });
    if (updated == 0) {
      return;
    }

    // Step 4: Audit the status change. No shop name, email, or event payload.
    jdbc.update(
        """
        INSERT INTO audit_log (
          id, tenant_id, actor_type, action, entity_type, entity_id, "before", "after"
        ) VALUES (?, ?, 'TSF', 'membership.changed', 'tenant', ?, ?::jsonb, ?::jsonb)
        """,
        UuidV7.generate(),
        message.tenantId(),
        message.tenantId().toString(),
        "{\"entitlement_status\":\"" + current.status + "\",\"ent_ver\":" + current.entVer + "}",
        "{\"entitlement_status\":\"" + status + "\",\"ent_ver\":" + entVer + "}");
  }

  private static String text(JsonNode data, String field) {
    JsonNode value = data.get(field);
    if (value == null || !value.isString() || value.asString().isBlank()) {
      return null;
    }
    return value.asString();
  }

  private static Instant expiry(JsonNode data) {
    JsonNode value = data.get("expires_at");
    if (value == null || value.isNull()) {
      return null;
    }
    if (!value.isString()) {
      throw new IllegalArgumentException("membership.changed is invalid");
    }
    return Instant.parse(value.asString());
  }

  private record Current(String status, long entVer) {}
}
