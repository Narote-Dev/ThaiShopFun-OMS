package com.thaishopfun.oms.inbox;

import com.thaishopfun.oms.order.OrderOptimisticLockException;
import com.thaishopfun.oms.stock.StockBusyException;
import com.thaishopfun.oms.stock.StockConflictException;
import com.thaishopfun.oms.stock.StockRetry;
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
 * <p>The claimed {@code next_attempt_at} is the lease token. A worker whose token no longer matches
 * the row does not write. Backoff is 30s, 2m, 10m, 30m, 1h, 3h, 6h. Those seven waits sum to about
 * 11 hours. The next failure is {@code DEAD}. Same-aggregate events take {@code
 * pg_advisory_xact_lock} for the transaction, so two workers cannot apply them together.
 */
@Component
public class InboxWorker {

  public static final String DEAD_METRIC = "oms.inbox.dead";
  public static final String UNKNOWN_METRIC = "oms.inbox.unknown_type";
  static final String MAX_ATTEMPTS_ERROR = "MAX_ATTEMPTS";

  /** {@code last_error} on a row pushed out because the shop cannot take business events yet. */
  static final String ENTITLEMENT_DEFERRED = "ENTITLEMENT_DEFERRED";

  static final Duration UNKNOWN_DEFER = Duration.ofHours(1);

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

  private static final String LAST_PROCESSED_VERSION =
      """
      SELECT MAX(aggregate_version)
      FROM inbox_event
      WHERE tenant_id = ?
        AND source = ?
        AND aggregate_id = ?
        AND status = 'PROCESSED'
        AND aggregate_version > 0
        AND id <> ?
        AND event_type NOT IN (%s)
      """
          .formatted(InboxEntitlementPolicy.entVerOrderedTypeLiterals());

  private final InboxProperties properties;
  private final InboxHandlerRegistry registry;
  private final InboxEntitlementPolicy policy;
  private final JdbcTemplate jdbc;
  private final TransactionTemplate claimTx;
  private final TransactionTemplate applyTx;
  private final TransactionTemplate failureTx;
  private final JsonMapper json;
  private final Counter deadEvents;
  private final Counter unknownTypes;

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
    this.claimTx = new TransactionTemplate(transactions);
    // Step 1: TransactionTemplate takes whole seconds and truncates. Ceil so 1.1s is 2s, not 1s.
    int timeoutSeconds =
        InboxLimits.handlerTransactionTimeoutSeconds(properties.getHandlerTimeout());
    this.applyTx = new TransactionTemplate(transactions);
    this.applyTx.setTimeout(timeoutSeconds);
    this.failureTx = new TransactionTemplate(transactions);
    this.failureTx.setTimeout(timeoutSeconds);
    this.json = json;
    this.deadEvents = Counter.builder(DEAD_METRIC).register(meters);
    this.unknownTypes = Counter.builder(UNKNOWN_METRIC).register(meters);
  }

  public int processAvailable() {
    return processAvailable(properties.getBatchSize());
  }

  public int processAvailable(int limit) {
    // Step 1: Claim with an empty tenant context. The definer returns id, tenant, and the lease.
    List<Claimed> claimed = claim(limit);
    int handled = 0;
    for (Claimed row : claimed) {
      try {
        processClaim(row);
        handled++;
      } catch (RuntimeException ex) {
        log.error("inbox event {} was not completed", row.id(), ex);
      }
    }
    return handled;
  }

  /** Applies one already-claimed row. Tests use this to show a stale lease cannot write. */
  void applyClaim(UUID id, UUID tenantId, OffsetDateTime leaseUntil) {
    processClaim(new Claimed(id, tenantId, leaseUntil));
  }

  private List<Claimed> claim(int limit) {
    int bounded = Math.min(Math.max(limit, 1), 1000);
    // Step 1: Milliseconds, rounded up. toSeconds() would shorten a 1.2s lease to 1s.
    long leaseMillis = InboxLimits.claimedLease(properties.getLease()).toMillis();
    List<Claimed> rows =
        claimTx.execute(
            status ->
                jdbc.query(
                    """
                    SELECT id, tenant_id, next_attempt_at
                    FROM claim_inbox_batch(?, (? * interval '1 millisecond'))
                    """,
                    (rs, row) ->
                        new Claimed(
                            rs.getObject("id", UUID.class),
                            rs.getObject("tenant_id", UUID.class),
                            rs.getObject("next_attempt_at", OffsetDateTime.class)),
                    bounded,
                    leaseMillis));
    return rows == null ? List.of() : rows;
  }

  /** Claims one batch and returns each row's lease. Tests check sub-second leases. */
  List<OffsetDateTime> claimLeases(int limit) {
    return claim(limit).stream().map(Claimed::leaseUntil).toList();
  }

  private void processClaim(Claimed claimed) {
    TenantContext.set(claimed.tenantId(), null);
    try {
      int attempts = 0;
      while (true) {
        try {
          applyTx.executeWithoutResult(
              status -> {
                jdbc.execute(
                    "SET LOCAL statement_timeout = " + properties.getHandlerTimeout().toMillis());
                apply(claimed);
              });
          break;
        } catch (InboxDeferException defer) {
          handleDefer(claimed, defer);
          break;
        } catch (RuntimeException ex) {
          if (attempts < 3 && retryableTransaction(ex)) {
            attempts++;
            continue;
          }
          recordFailure(claimed, ex);
          break;
        }
      }
    } finally {
      TenantContext.clear();
    }
  }

  private void handleDefer(Claimed claimed, InboxDeferException defer) {
    failureTx.executeWithoutResult(
        status -> {
          InboxRow row = lock(claimed.id());
          if (row == null) {
            return;
          }
          if (!"RECEIVED".equals(row.status()) && !"FAILED".equals(row.status())) {
            return;
          }
          if (!leaseMatches(row.nextAttemptAt(), claimed.leaseUntil())) {
            return;
          }
          Instant received = row.receivedAt();
          if (received != null
              && received.isBefore(
                  jdbc
                      .queryForObject("SELECT now()", OffsetDateTime.class)
                      .toInstant()
                      .minus(properties.getMaxDefer()))) {
            upsertOrderEventWithoutOrder(row);
            recordFailure(new Claimed(row.id(), row.tenantId(), claimed.leaseUntil()), new RuntimeException("ORDER_EVENT_WITHOUT_ORDER"));
            return;
          }
          pushBack(row, defer.delay() == null ? properties.getDeferDelay() : defer.delay());
        });
  }

  private void upsertOrderEventWithoutOrder(InboxRow row) {
    int updated =
        jdbc.update(
            """
            UPDATE reconciliation_issue
            SET details = ?::jsonb, updated_at = now()
            WHERE tenant_id = ? AND rule = 'ORDER_EVENT_WITHOUT_ORDER' AND order_id IS NULL
              AND status <> 'RESOLVED'
            """,
            "{\"inbox_event_id\":\"" + row.id() + "\"}",
            row.tenantId());
    if (updated == 0) {
      jdbc.update(
          """
          INSERT INTO reconciliation_issue (id, tenant_id, run_id, rule, order_id, details, status)
          VALUES (?, ?, ?, 'ORDER_EVENT_WITHOUT_ORDER', NULL, ?::jsonb, 'OPEN')
          """,
          UUID.randomUUID(),
          row.tenantId(),
          row.id(),
          "{\"inbox_event_id\":\"" + row.id() + "\"}");
    }
  }

  private static boolean retryableTransaction(RuntimeException ex) {
    if (ex instanceof StockConflictException || ex instanceof OrderOptimisticLockException) {
      return true;
    }
    if (ex instanceof StockBusyException) {
      return true;
    }
    return StockRetry.classify(ex) != null;
  }

  private void apply(Claimed claimed) {
    // Step 1: Lock the claimed row. A lost lease or a finished row is left alone.
    InboxRow row = lock(claimed.id());
    if (row == null || !claimed.tenantId().equals(row.tenantId())) {
      return;
    }
    if (!"RECEIVED".equals(row.status()) && !"FAILED".equals(row.status())) {
      return;
    }
    if (!leaseMatches(row.nextAttemptAt(), claimed.leaseUntil())) {
      return;
    }
    // Step 2: A claim past the ladder means an earlier attempt never recorded DEAD.
    if (row.attempts() > BACKOFF.length + 1) {
      markDead(row, MAX_ATTEMPTS_ERROR);
      return;
    }
    // Step 3: Suspended and expired shops keep the event. membership.changed still runs.
    // The marker is what a later ACTIVE/GRACE membership wakes. A normal failure backoff is not.
    if (policy.defer(row.entitlementStatus(), row.expiresAt(), row.eventType())) {
      pushBack(row, properties.getSuspendDefer(), ENTITLEMENT_DEFERRED);
      return;
    }
    InboxHandler handler = registry.find(row.eventType());
    if (handler == null) {
      // Step 4: No handler yet. Leave RECEIVED and do not burn an attempt.
      log.warn(
          "inbox event {} type {} has no handler; deferred {}",
          row.eventId(),
          row.eventType(),
          UNKNOWN_DEFER);
      unknownTypes.increment();
      pushBack(row, UNKNOWN_DEFER);
      return;
    }
    // Step 5: One aggregate at a time, then drop a version that is already applied.
    // Events ordered by ent_ver are not part of this history, and do not use it.
    lockAggregate(row);
    Long lastVersion = lastProcessedVersion(row);
    boolean entVerOrdered = InboxEntitlementPolicy.ordersByEntVer(row.eventType());
    boolean stale =
        !entVerOrdered
            && row.aggregateVersion() > 0
            && lastVersion != null
            && row.aggregateVersion() <= lastVersion;
    // TODO: Handlers must apply the full snapshot in data until REST refetch exists (T10).
    // gap=true means aggregate_version skipped at least one version. Do not assume a delta.
    boolean gap =
        !stale
            && row.aggregateVersion() > 0
            && lastVersion != null
            && row.aggregateVersion() > lastVersion + 1;
    if (!stale) {
      // Step 6: Handler writes and PROCESSED commit together. A throw rolls both back.
      handler.handle(row.message(gap));
    }
    markProcessed(row);
  }

  private InboxRow lock(UUID id) {
    return jdbc.query(
        """
        SELECT e.id, e.tenant_id, e.source, e.event_id, e.event_type, e.aggregate_id,
               e.aggregate_version, e.payload::text AS payload, e.status, e.attempts,
               e.next_attempt_at, e.received_at, t.entitlement_status, t.entitlement_expires_at
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
          OffsetDateTime receivedAt = rs.getObject("received_at", OffsetDateTime.class);
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
              rs.getObject("next_attempt_at", OffsetDateTime.class),
              receivedAt == null ? null : receivedAt.toInstant(),
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
    // Step 1: Skip ent_ver-ordered types so a membership version cannot hide a business event.
    return jdbc.queryForObject(
        LAST_PROCESSED_VERSION,
        Long.class,
        row.tenantId(),
        row.source(),
        row.aggregateId(),
        row.id());
  }

  private void pushBack(InboxRow row, Duration delay) {
    pushBack(row, delay, null);
  }

  private void pushBack(InboxRow row, Duration delay, String lastError) {
    long deferMillis = Math.max(delay.toMillis(), 0);
    int updated =
        jdbc.update(
            """
            UPDATE inbox_event
            SET attempts = GREATEST(attempts - 1, 0),
                next_attempt_at = pg_catalog.now() + (? * interval '1 millisecond'),
                last_error = CASE WHEN ? THEN ? ELSE last_error END
            WHERE id = ?
              AND next_attempt_at = ?
              AND status IN ('RECEIVED', 'FAILED')
            """,
            deferMillis,
            lastError != null,
            lastError == null ? "" : lastError,
            row.id(),
            row.nextAttemptAt());
    if (updated != 1) {
      throw new IllegalStateException("inbox lease was lost");
    }
  }

  private void markProcessed(InboxRow row) {
    int updated =
        jdbc.update(
            """
            UPDATE inbox_event
            SET status = 'PROCESSED',
                processed_at = pg_catalog.now(),
                last_error = NULL,
                next_attempt_at = NULL
            WHERE id = ?
              AND next_attempt_at = ?
              AND status IN ('RECEIVED', 'FAILED')
            """,
            row.id(),
            row.nextAttemptAt());
    if (updated != 1) {
      throw new IllegalStateException("inbox row was not marked PROCESSED");
    }
  }

  private void markDead(InboxRow row, String message) {
    int updated =
        jdbc.update(
            """
            UPDATE inbox_event
            SET status = 'DEAD', last_error = ?, next_attempt_at = NULL
            WHERE id = ?
              AND next_attempt_at = ?
              AND status IN ('RECEIVED', 'FAILED')
            """,
            message,
            row.id(),
            row.nextAttemptAt());
    if (updated != 1) {
      throw new IllegalStateException("inbox lease was lost");
    }
    deadEvents.increment();
    log.error("inbox event {} is DEAD after {} attempts", row.eventId(), row.attempts());
  }

  private void recordFailure(Claimed claimed, RuntimeException ex) {
    String message = sanitize(ex);
    boolean stop = nonRetryable(ex);
    failureTx.executeWithoutResult(
        status -> {
          InboxRow row = lock(claimed.id());
          if (row == null) {
            return;
          }
          if (!"RECEIVED".equals(row.status()) && !"FAILED".equals(row.status())) {
            return;
          }
          if (!leaseMatches(row.nextAttemptAt(), claimed.leaseUntil())) {
            return;
          }
          // Step 1: Validation failures and an exhausted ladder go straight to DEAD.
          int attempt = Math.max(row.attempts(), 1);
          if (stop || attempt >= BACKOFF.length + 1) {
            markDead(row, stop ? message : message);
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
                AND next_attempt_at = ?
                AND status IN ('RECEIVED', 'FAILED')
              """,
              message,
              delay.toMillis(),
              row.id(),
              row.nextAttemptAt());
        });
  }

  private static boolean nonRetryable(Throwable ex) {
    for (Throwable current = ex; current != null; current = current.getCause()) {
      if (current instanceof NonRetryableInboxException) {
        return true;
      }
    }
    return false;
  }

  private static boolean leaseMatches(OffsetDateTime row, OffsetDateTime claimed) {
    if (row == null || claimed == null) {
      return false;
    }
    return row.toInstant().equals(claimed.toInstant());
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

  private record Claimed(UUID id, UUID tenantId, OffsetDateTime leaseUntil) {}

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
      OffsetDateTime nextAttemptAt,
      Instant receivedAt,
      String entitlementStatus,
      Instant expiresAt) {

    private InboxMessage message(boolean gap) {
      return new InboxMessage(
          id, tenantId, source, eventId, eventType, aggregateId, aggregateVersion, gap, payload);
    }
  }
}
