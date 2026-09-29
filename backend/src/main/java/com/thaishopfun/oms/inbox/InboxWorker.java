package com.thaishopfun.oms.inbox;

import com.thaishopfun.oms.tenant.TenantContext;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Claims inbox rows, then handles each one in its own tenant transaction.
 *
 * <p>The claim commits first ({@code SKIP LOCKED}, attempts + 1, lease on {@code next_attempt_at}).
 * The following transaction is handler writes plus {@code status = PROCESSED}. A throw rolls that
 * transaction back, so a PROCESSED row is never left behind a failed handler. The failure is then
 * recorded in a new transaction: backoff, or {@code DEAD} after the schedule is exhausted.
 *
 * <p>Backoff is 30s, 2m, 10m, 30m, 1h, 3h, 6h. Those seven waits sum to about 11 hours. The next
 * failure is {@code DEAD}. Same-aggregate events take {@code pg_advisory_xact_lock} for the
 * transaction, so two workers cannot apply them together.
 */
@Component
public class InboxWorker {

  public static final String DEAD_METRIC = "oms.inbox.dead";

  static final Duration[] BACKOFF = {
    Duration.ofSeconds(30),
    Duration.ofMinutes(2),
    Duration.ofMinutes(10),
    Duration.ofMinutes(30),
    Duration.ofHours(1),
    Duration.ofHours(3),
    Duration.ofHours(6)
  };

  private static final Logger log = LoggerFactory.getLogger(InboxWorker.class);

  private final InboxProperties properties;
  private final InboxHandlerRegistry registry;
  private final InboxEntitlementPolicy policy;
  private final JdbcTemplate jdbc;
  private final TransactionTemplate transactions;
  private final JsonMapper json;
  private final Counter deadEvents;

  public InboxWorker(
      InboxProperties properties,
      InboxHandlerRegistry registry,
      InboxEntitlementPolicy policy,
      JdbcTemplate jdbc,
      PlatformTransactionManager transactions,
      JsonMapper json,
      MeterRegistry meters) {
    this.properties = properties;
    this.registry = registry;
    this.policy = policy;
    this.jdbc = jdbc;
    this.transactions = new TransactionTemplate(transactions);
    this.json = json;
    this.deadEvents = Counter.builder(DEAD_METRIC).register(meters);
  }

  public int processAvailable() {
    return processAvailable(properties.getBatchSize());
  }

  public int processAvailable(int limit) {
    // Step 1: Claim with an empty tenant context. The definer returns id and tenant_id only.
    List<Claimed> claimed = claim(limit);
    int handled = 0;
    for (Claimed row : claimed) {
      try {
        processClaim(row);
        handled++;
      } catch (RuntimeException ex) {
        log.error("inbox event {} was not completed", row.id());
      }
    }
    return handled;
  }

  private List<Claimed> claim(int limit) {
    int bounded = Math.min(Math.max(limit, 1), 1000);
    long leaseSeconds = Math.max(properties.getLease().toSeconds(), 1);
    List<Claimed> rows =
        transactions.execute(
            status ->
                jdbc.query(
                    """
                    SELECT id, tenant_id
                    FROM claim_inbox_batch(?, (? * interval '1 second'))
                    """,
                    (rs, row) ->
                        new Claimed(
                            rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class)),
                    bounded,
                    leaseSeconds));
    return rows == null ? List.of() : rows;
  }

  private void processClaim(Claimed claimed) {
    TenantContext.set(claimed.tenantId(), null);
    try {
      try {
        transactions.executeWithoutResult(status -> apply(claimed));
      } catch (RuntimeException ex) {
        // Step 1: The handler transaction is gone. Record FAILED or DEAD on its own.
        recordFailure(claimed, ex);
      }
    } finally {
      TenantContext.clear();
    }
  }

  private void apply(Claimed claimed) {
    // Step 1: Lock the claimed row. A worker that lost the race sees PROCESSED and stops.
    InboxRow row = lock(claimed.id());
    if (row == null || !claimed.tenantId().equals(row.tenantId())) {
      return;
    }
    if (!"RECEIVED".equals(row.status()) && !"FAILED".equals(row.status())) {
      return;
    }
    // Step 2: Suspended and expired shops keep the event. membership.changed still runs.
    if (policy.defer(row.entitlementStatus(), row.expiresAt(), row.eventType())) {
      defer(row.id());
      return;
    }
    // Step 3: One aggregate at a time, then drop a version that is already applied.
    lockAggregate(row);
    Long lastVersion = lastProcessedVersion(row);
    boolean stale =
        row.aggregateVersion() > 0 && lastVersion != null && row.aggregateVersion() <= lastVersion;
    boolean gap =
        !stale
            && row.aggregateVersion() > 0
            && lastVersion != null
            && row.aggregateVersion() > lastVersion + 1;
    if (!stale) {
      InboxHandler handler = registry.find(row.eventType());
      if (handler != null) {
        // Step 4: Handler writes and PROCESSED commit together. A throw rolls both back.
        handler.handle(row.message(gap));
      }
    }
    markProcessed(row.id());
  }

  private InboxRow lock(UUID id) {
    return jdbc.query(
        """
        SELECT e.id, e.tenant_id, e.source, e.event_id, e.event_type, e.aggregate_id,
               e.aggregate_version, e.payload::text AS payload, e.status, e.attempts,
               t.entitlement_status, t.entitlement_expires_at
        FROM inbox_event AS e
        JOIN tenant AS t ON t.id = e.tenant_id
        WHERE e.id = ?
        FOR UPDATE OF e
        """,
        rs -> {
          if (!rs.next()) {
            return null;
          }
          OffsetDateTime expires = rs.getObject("entitlement_expires_at", OffsetDateTime.class);
          return new InboxRow(
              rs.getObject("id", UUID.class),
              rs.getObject("tenant_id", UUID.class),
              rs.getString("source"),
              rs.getString("event_id"),
              rs.getString("event_type"),
              rs.getString("aggregate_id"),
              rs.getLong("aggregate_version"),
              json.readTree(rs.getString("payload")),
              rs.getString("status"),
              rs.getInt("attempts"),
              rs.getString("entitlement_status"),
              expires == null ? null : expires.toInstant());
        },
        id);
  }

  private void lockAggregate(InboxRow row) {
    String key = row.tenantId() + "\u001f" + row.source() + "\u001f" + row.aggregateId();
    jdbc.query(
        "SELECT pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended(?, 11))",
        rs -> {
          rs.next();
          return null;
        },
        key);
  }

  private Long lastProcessedVersion(InboxRow row) {
    return jdbc.queryForObject(
        """
        SELECT MAX(aggregate_version)
        FROM inbox_event
        WHERE tenant_id = ?
          AND source = ?
          AND aggregate_id = ?
          AND status = 'PROCESSED'
          AND aggregate_version > 0
          AND id <> ?
        """,
        Long.class,
        row.tenantId(),
        row.source(),
        row.aggregateId(),
        row.id());
  }

  private void defer(UUID id) {
    long deferMillis = Math.max(properties.getSuspendDefer().toMillis(), 0);
    jdbc.update(
        """
        UPDATE inbox_event
        SET attempts = GREATEST(attempts - 1, 0),
            next_attempt_at = pg_catalog.now() + (? * interval '1 millisecond')
        WHERE id = ?
          AND status IN ('RECEIVED', 'FAILED')
        """,
        deferMillis,
        id);
  }

  private void markProcessed(UUID id) {
    int updated =
        jdbc.update(
            """
            UPDATE inbox_event
            SET status = 'PROCESSED',
                processed_at = pg_catalog.now(),
                last_error = NULL,
                next_attempt_at = NULL
            WHERE id = ?
              AND status IN ('RECEIVED', 'FAILED')
            """,
            id);
    if (updated != 1) {
      throw new IllegalStateException("inbox row was not marked PROCESSED");
    }
  }

  private void recordFailure(Claimed claimed, RuntimeException ex) {
    String message = sanitize(ex);
    transactions.executeWithoutResult(
        status -> {
          InboxRow row = lock(claimed.id());
          if (row == null) {
            return;
          }
          if (!"RECEIVED".equals(row.status()) && !"FAILED".equals(row.status())) {
            return;
          }
          // Step 1: Seven waits, then DEAD. The claim already incremented attempts.
          int attempt = Math.max(row.attempts(), 1);
          if (attempt >= BACKOFF.length + 1) {
            jdbc.update(
                """
                UPDATE inbox_event
                SET status = 'DEAD', last_error = ?, next_attempt_at = NULL
                WHERE id = ?
                  AND status IN ('RECEIVED', 'FAILED')
                """,
                message,
                row.id());
            deadEvents.increment();
            log.error("inbox event {} is DEAD after {} attempts", row.eventId(), attempt);
            return;
          }
          Duration delay = jitter(BACKOFF[attempt - 1]);
          jdbc.update(
              """
              UPDATE inbox_event
              SET status = 'FAILED',
                  last_error = ?,
                  next_attempt_at = pg_catalog.now() + (? * interval '1 millisecond')
              WHERE id = ?
                AND status IN ('RECEIVED', 'FAILED')
              """,
              message,
              delay.toMillis(),
              row.id());
        });
  }

  private Duration jitter(Duration base) {
    double ratio = properties.getJitterRatio();
    if (ratio <= 0) {
      return base;
    }
    double clamped = Math.min(ratio, 1);
    double factor = 1 + (ThreadLocalRandom.current().nextDouble() * 2 - 1) * clamped;
    long millis = Math.max(1, Math.round(base.toMillis() * factor));
    return Duration.ofMillis(millis);
  }

  private static String sanitize(RuntimeException ex) {
    String message = ex.getMessage();
    if (message == null || message.isBlank()) {
      message = ex.getClass().getSimpleName();
    }
    message = message.replace('\n', ' ').replace('\r', ' ');
    if (message.length() > 500) {
      message = message.substring(0, 500);
    }
    return message;
  }

  private record Claimed(UUID id, UUID tenantId) {}

  private record InboxRow(
      UUID id,
      UUID tenantId,
      String source,
      String eventId,
      String eventType,
      String aggregateId,
      long aggregateVersion,
      JsonNode payload,
      String status,
      int attempts,
      String entitlementStatus,
      Instant expiresAt) {

    private InboxMessage message(boolean gap) {
      return new InboxMessage(
          id, tenantId, source, eventId, eventType, aggregateId, aggregateVersion, gap, payload);
    }
  }
}
