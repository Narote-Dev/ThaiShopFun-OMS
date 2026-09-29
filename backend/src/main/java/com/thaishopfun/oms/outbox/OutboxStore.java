package com.thaishopfun.oms.outbox;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Database work for the publisher. Each method commits on its own, never around the HTTP call. */
@Service
public class OutboxStore {

  private static final Logger log = LoggerFactory.getLogger(OutboxStore.class);

  private final JdbcTemplate jdbc;

  public OutboxStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public record Claimed(UUID id, UUID tenantId) {}

  public record Held(
      UUID id,
      UUID tenantId,
      String eventType,
      String payload,
      int attempts,
      OffsetDateTime leaseUntil) {}

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public List<Claimed> claim(int batchSize, Duration lease) {
    // Step 1: SECURITY DEFINER returns ids only. Commit before HTTP so the lease is visible.
    String interval = lease.toSeconds() + " seconds";
    return jdbc.query(
        "SELECT id, tenant_id FROM claim_outbox_batch(?, ?::interval)",
        ps -> {
          ps.setInt(1, batchSize);
          ps.setString(2, interval);
        },
        (rs, rowNum) ->
            new Claimed(rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class)));
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
  public List<Held> loadInFlight(List<UUID> ids) {
    // Step 2: RLS shows only the tenant set on this transaction. Payload stays out of the logs.
    List<Held> rows = new ArrayList<>();
    for (UUID id : ids) {
      Held held =
          jdbc.query(
              """
              SELECT id, tenant_id, event_type, payload::text AS payload, attempts, lease_until
              FROM outbox_event
              WHERE id = ? AND status = 'IN_FLIGHT'
              """,
              ps -> ps.setObject(1, id),
              rs -> {
                if (!rs.next()) {
                  return null;
                }
                return new Held(
                    rs.getObject("id", UUID.class),
                    rs.getObject("tenant_id", UUID.class),
                    rs.getString("event_type"),
                    rs.getString("payload"),
                    rs.getInt("attempts"),
                    rs.getObject("lease_until", OffsetDateTime.class));
              });
      if (held != null) {
        rows.add(held);
      }
    }
    return rows;
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public boolean markSent(Held held) {
    // Step 3: Compare attempts so a reclaimed lease is not overwritten.
    int updated =
        jdbc.update(
            """
            UPDATE outbox_event
            SET status = 'SENT', sent_at = now(), lease_until = NULL, next_attempt_at = NULL
            WHERE id = ? AND status = 'IN_FLIGHT' AND attempts = ?
            """,
            ps -> {
              ps.setObject(1, held.id());
              ps.setInt(2, held.attempts());
            });
    if (updated == 0) {
      log.warn("outbox lost lease event_id={} attempts={}", held.id(), held.attempts());
    }
    return updated == 1;
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public boolean scheduleRetry(Held held, OffsetDateTime nextAttemptAt) {
    // Step 4: PENDING plus a future next_attempt_at hides the row until the backoff elapses.
    int updated =
        jdbc.update(
            """
            UPDATE outbox_event
            SET status = 'PENDING', next_attempt_at = ?, lease_until = NULL
            WHERE id = ? AND status = 'IN_FLIGHT' AND attempts = ?
            """,
            ps -> {
              ps.setObject(1, nextAttemptAt);
              ps.setObject(2, held.id());
              ps.setInt(3, held.attempts());
            });
    return updated == 1;
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public boolean markDead(Held held, int httpStatus) {
    // Step 5: DEAD is terminal until an owner or admin retries it. Alert without the payload.
    int updated =
        jdbc.update(
            """
            UPDATE outbox_event
            SET status = 'DEAD', lease_until = NULL, next_attempt_at = NULL
            WHERE id = ? AND status = 'IN_FLIGHT' AND attempts = ?
            """,
            ps -> {
              ps.setObject(1, held.id());
              ps.setInt(2, held.attempts());
            });
    if (updated == 1) {
      log.error(
          "outbox alert status=DEAD event_id={} tenant_id={} event_type={} attempts={} http_status={}",
          held.id(),
          held.tenantId(),
          held.eventType(),
          held.attempts(),
          httpStatus);
    }
    return updated == 1;
  }

  public static OffsetDateTime atUtc(java.time.Instant instant) {
    return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
  }
}
