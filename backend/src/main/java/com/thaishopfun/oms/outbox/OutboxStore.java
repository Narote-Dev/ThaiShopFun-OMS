package com.thaishopfun.oms.outbox;

import com.thaishopfun.oms.tenant.TenantContext;
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

  /**
   * One claim generation. {@code leaseUntil} and {@code attempts} are read before that claim
   * commits.
   */
  public record Claimed(UUID id, UUID tenantId, int attempts, OffsetDateTime leaseUntil) {}

  public record Held(
      UUID id,
      UUID tenantId,
      String eventType,
      String payload,
      int attempts,
      OffsetDateTime leaseUntil) {}

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public List<Claimed> claim(int batchSize, Duration lease) {
    // Step 1: SECURITY DEFINER returns ids only. The row locks are held until this method commits.
    String interval = lease.toSeconds() + " seconds";
    List<Claimed> ids =
        jdbc.query(
            "SELECT id, tenant_id FROM claim_outbox_batch(?, ?::interval)",
            ps -> {
              ps.setInt(1, batchSize);
              ps.setString(2, interval);
            },
            (rs, rowNum) ->
                new Claimed(
                    rs.getObject("id", UUID.class),
                    rs.getObject("tenant_id", UUID.class),
                    0,
                    null));
    // Step 2: Read this generation before commit. A later reclaim gets a different lease.
    List<Claimed> claimed = new ArrayList<>();
    for (Claimed row : ids) {
      bindTenant(row.tenantId());
      Claimed full =
          jdbc.query(
              """
              SELECT attempts, lease_until
              FROM outbox_event
              WHERE id = ? AND status = 'IN_FLIGHT'
              """,
              ps -> ps.setObject(1, row.id()),
              rs -> {
                if (!rs.next()) {
                  return null;
                }
                return new Claimed(
                    row.id(),
                    row.tenantId(),
                    rs.getInt("attempts"),
                    rs.getObject("lease_until", OffsetDateTime.class));
              });
      if (full == null || full.leaseUntil() == null) {
        throw new IllegalStateException("claimed outbox row is not visible");
      }
      claimed.add(full);
    }
    bindTenant(TenantContext.tenantId());
    return claimed;
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
  public List<Held> loadInFlight(List<Claimed> claimed) {
    // Step 3: RLS shows only this tenant. A reclaimed lease does not match, so it is not sent.
    List<Held> rows = new ArrayList<>();
    for (Claimed claimedRow : claimed) {
      Held held =
          jdbc.query(
              """
              SELECT id, tenant_id, event_type, payload::text AS payload, attempts, lease_until
              FROM outbox_event
              WHERE id = ? AND status = 'IN_FLIGHT' AND attempts = ? AND lease_until = ?
              """,
              ps -> {
                ps.setObject(1, claimedRow.id());
                ps.setInt(2, claimedRow.attempts());
                ps.setObject(3, claimedRow.leaseUntil());
              },
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
    // Step 4: Match this lease. An admin retry resets attempts, so attempts alone can repeat.
    int updated =
        jdbc.update(
            """
            UPDATE outbox_event
            SET status = 'SENT', sent_at = now(), lease_until = NULL, next_attempt_at = NULL
            WHERE id = ? AND status = 'IN_FLIGHT' AND attempts = ? AND lease_until = ?
            """,
            ps -> {
              ps.setObject(1, held.id());
              ps.setInt(2, held.attempts());
              ps.setObject(3, held.leaseUntil());
            });
    if (updated == 0) {
      log.warn("outbox lost lease event_id={} attempts={}", held.id(), held.attempts());
    }
    return updated == 1;
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public boolean scheduleRetry(Held held, OffsetDateTime nextAttemptAt) {
    // Step 5: PENDING plus a future next_attempt_at hides the row until the backoff elapses.
    int updated =
        jdbc.update(
            """
            UPDATE outbox_event
            SET status = 'PENDING', next_attempt_at = ?, lease_until = NULL
            WHERE id = ? AND status = 'IN_FLIGHT' AND attempts = ? AND lease_until = ?
            """,
            ps -> {
              ps.setObject(1, nextAttemptAt);
              ps.setObject(2, held.id());
              ps.setInt(3, held.attempts());
              ps.setObject(4, held.leaseUntil());
            });
    return updated == 1;
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public boolean markDead(Held held, int httpStatus) {
    // Step 6: DEAD is terminal until an owner or admin retries it. Alert without the payload.
    int updated =
        jdbc.update(
            """
            UPDATE outbox_event
            SET status = 'DEAD', lease_until = NULL, next_attempt_at = NULL
            WHERE id = ? AND status = 'IN_FLIGHT' AND attempts = ? AND lease_until = ?
            """,
            ps -> {
              ps.setObject(1, held.id());
              ps.setInt(2, held.attempts());
              ps.setObject(3, held.leaseUntil());
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

  private void bindTenant(UUID tenantId) {
    String value = tenantId == null ? "" : tenantId.toString();
    String applied =
        jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, value);
    if (applied == null) {
      throw new IllegalStateException("set_config returned no row");
    }
  }

  public static OffsetDateTime atUtc(java.time.Instant instant) {
    return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
  }
}
