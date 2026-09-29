package com.thaishopfun.oms.inbox;

import com.thaishopfun.oms.auth.UuidV7;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Applies {@code membership.changed} to {@code tenant}. A lower {@code ent_ver} is ignored. The
 * audit row stores status and {@code ent_ver} only. Moving to {@code ACTIVE} or {@code GRACE} wakes
 * rows deferred for entitlement ({@code last_error = ENTITLEMENT_DEFERRED}). A normal failure
 * backoff keeps its {@code next_attempt_at}.
 */
@Component
public class MembershipChangedHandler implements InboxHandler {

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
    // Step 1: Validate before any write. A bad payload is not retried.
    MembershipPayload incoming = MembershipPayload.parse(message.payload().path("data"));

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
              ps.setString(1, incoming.tier);
              ps.setString(2, incoming.status);
              if (incoming.expiresAt == null) {
                ps.setNull(3, Types.TIMESTAMP_WITH_TIMEZONE);
              } else {
                ps.setObject(3, OffsetDateTime.ofInstant(incoming.expiresAt, ZoneOffset.UTC));
              }
              ps.setLong(4, incoming.entVer);
              ps.setObject(5, message.tenantId());
              ps.setLong(6, incoming.entVer);
            });
    if (updated == 0) {
      return;
    }

    // Step 4: Wake only entitlement defers. A FAILED row on the normal backoff ladder stays put.
    if ("ACTIVE".equals(incoming.status) || "GRACE".equals(incoming.status)) {
      jdbc.update(
          """
          UPDATE inbox_event
          SET next_attempt_at = pg_catalog.now()
          WHERE tenant_id = ?
            AND id <> ?
            AND status IN ('RECEIVED', 'FAILED')
            AND last_error = ?
            AND next_attempt_at > pg_catalog.now()
          """,
          message.tenantId(),
          message.id(),
          InboxWorker.ENTITLEMENT_DEFERRED);
    }

    // Step 5: Audit the status change. No shop name, email, or event payload.
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
        "{\"entitlement_status\":\"" + incoming.status + "\",\"ent_ver\":" + incoming.entVer + "}");
  }

  private record Current(String status, long entVer) {}
}
