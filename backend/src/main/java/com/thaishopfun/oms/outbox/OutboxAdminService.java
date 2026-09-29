package com.thaishopfun.oms.outbox;

import com.thaishopfun.oms.auth.TenantSessionService;
import com.thaishopfun.oms.auth.TenantSnapshot;
import com.thaishopfun.oms.auth.UuidV7;
import com.thaishopfun.oms.tenant.TenantContext;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Owner and admin retry of DEAD outbox rows. RLS hides every other tenant. */
@Service
public class OutboxAdminService {

  private final JdbcTemplate jdbc;
  private final TenantSessionService sessions;

  public OutboxAdminService(JdbcTemplate jdbc, TenantSessionService sessions) {
    this.jdbc = jdbc;
    this.sessions = sessions;
  }

  @Transactional(readOnly = true)
  public List<OutboxDeadEvent> listDead() {
    // Step 1: The caller is already inside a tenant transaction. STAFF cannot list.
    requireAdmin();
    return jdbc.query(
        """
        SELECT id, aggregate_type, aggregate_id, event_type, status, attempts,
               created_at, next_attempt_at
        FROM outbox_event
        WHERE status = 'DEAD'
        ORDER BY created_at, id
        """,
        (rs, rowNum) -> map(rs));
  }

  @Transactional
  public OutboxRetryResponse retry(UUID eventId, String remoteAddr) {
    // Step 2: Only DEAD rows move. A missing id and another tenant look the same: not found.
    TenantSnapshot snapshot = requireAdmin();
    String status =
        jdbc.query(
            "SELECT status, attempts FROM outbox_event WHERE id = ?",
            ps -> ps.setObject(1, eventId),
            rs -> rs.next() ? rs.getString("status") : null);
    if (status == null) {
      throw new OutboxAccessException(404, "NOT_FOUND", "Outbox event not found");
    }
    if (!"DEAD".equals(status)) {
      throw new OutboxAccessException(409, "CONFLICT", "Only DEAD events can be retried");
    }
    int attempts =
        jdbc.query(
            "SELECT attempts FROM outbox_event WHERE id = ?",
            ps -> ps.setObject(1, eventId),
            rs -> rs.next() ? rs.getInt(1) : 0);
    int updated =
        jdbc.update(
            """
            UPDATE outbox_event
            SET status = 'PENDING', attempts = 0, next_attempt_at = NULL, lease_until = NULL
            WHERE id = ? AND status = 'DEAD'
            """,
            ps -> ps.setObject(1, eventId));
    if (updated != 1) {
      throw new OutboxAccessException(409, "CONFLICT", "Only DEAD events can be retried");
    }
    // Step 3: Audit the transition. The payload is not copied; it may hold shop data later.
    writeAudit(snapshot, eventId, attempts, remoteAddr);
    return new OutboxRetryResponse(eventId, "PENDING", 0);
  }

  private TenantSnapshot requireAdmin() {
    TenantSnapshot snapshot =
        sessions.load(TenantContext.requireTenantId(), TenantContext.requireUserId());
    if (!"OWNER".equals(snapshot.role()) && !"ADMIN".equals(snapshot.role())) {
      throw new OutboxAccessException(403, "FORBIDDEN", "OWNER or ADMIN role is required");
    }
    return snapshot;
  }

  private void writeAudit(TenantSnapshot snapshot, UUID eventId, int attempts, String remoteAddr) {
    String before = "{\"status\":\"DEAD\",\"attempts\":" + attempts + "}";
    String after = "{\"status\":\"PENDING\",\"attempts\":0}";
    jdbc.update(
        """
        INSERT INTO audit_log
          (id, tenant_id, actor_type, actor_id, action, entity_type, entity_id, "before", "after", ip)
        VALUES (?, ?, 'USER', ?, 'outbox.retry', 'outbox_event', ?, ?::jsonb, ?::jsonb, ?::inet)
        """,
        ps -> {
          ps.setObject(1, UuidV7.generate());
          ps.setObject(2, snapshot.tenantId());
          ps.setString(3, snapshot.userId().toString());
          ps.setString(4, eventId.toString());
          ps.setString(5, before);
          ps.setString(6, after);
          if (remoteAddr == null || remoteAddr.isBlank() || remoteAddr.indexOf(' ') >= 0) {
            ps.setNull(7, Types.VARCHAR);
          } else {
            ps.setString(7, remoteAddr);
          }
        });
  }

  private static OutboxDeadEvent map(ResultSet rs) throws SQLException {
    return new OutboxDeadEvent(
        rs.getObject("id", UUID.class),
        rs.getString("aggregate_type"),
        rs.getString("aggregate_id"),
        rs.getString("event_type"),
        rs.getString("status"),
        rs.getInt("attempts"),
        instant(rs, "created_at"),
        instant(rs, "next_attempt_at"));
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
    return value == null ? null : value.toInstant();
  }
}
