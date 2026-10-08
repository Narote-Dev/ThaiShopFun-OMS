package com.thaishopfun.oms.order.hold;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class OrderHoldRetryRepository {

  private final JdbcTemplate jdbc;
  private final OrderHoldProperties properties;

  public OrderHoldRetryRepository(JdbcTemplate jdbc, OrderHoldProperties properties) {
    this.jdbc = jdbc;
    this.properties = properties;
  }

  public void clear(UUID tenantId, UUID orderId) {
    jdbc.update(
        "DELETE FROM order_hold_retry WHERE tenant_id = ? AND order_id = ?", tenantId, orderId);
  }

  public void recordBackoff(UUID tenantId, UUID orderId, String lastError, Instant now) {
    Instant placeholder = now;
    Integer attempts =
        jdbc.queryForObject(
            """
            INSERT INTO order_hold_retry (tenant_id, order_id, attempts, next_attempt_at, last_error, updated_at)
            VALUES (?, ?, 1, ?, ?, ?)
            ON CONFLICT (tenant_id, order_id) DO UPDATE SET
              attempts = order_hold_retry.attempts + 1,
              last_error = EXCLUDED.last_error,
              updated_at = EXCLUDED.updated_at
            RETURNING attempts
            """,
            Integer.class,
            tenantId,
            orderId,
            OffsetDateTime.ofInstant(placeholder, ZoneOffset.UTC),
            lastError,
            OffsetDateTime.ofInstant(now, ZoneOffset.UTC));
    int attempt = attempts == null ? 1 : attempts;
    Instant next = scheduleNext(attempt, now);
    jdbc.update(
        """
        UPDATE order_hold_retry
        SET next_attempt_at = ?, updated_at = ?
        WHERE tenant_id = ? AND order_id = ?
        """,
        OffsetDateTime.ofInstant(next, ZoneOffset.UTC),
        OffsetDateTime.ofInstant(now, ZoneOffset.UTC),
        tenantId,
        orderId);
  }

  public long countInBackoff(UUID tenantId, Instant now) {
    Long count =
        jdbc.queryForObject(
            """
            SELECT count(*) FROM order_hold_retry
            WHERE tenant_id = ? AND next_attempt_at > ?
            """,
            Long.class,
            tenantId,
            OffsetDateTime.ofInstant(now, ZoneOffset.UTC));
    return count == null ? 0 : count;
  }

  Instant scheduleNext(int attempts, Instant now) {
    long baseMs = properties.getBackoffBase().toMillis();
    long maxMs = properties.getBackoffMax().toMillis();
    int exp = Math.max(0, attempts - 1);
    long raw = Math.min(maxMs, baseMs * (1L << Math.min(exp, 30)));
    double jitter = properties.getBackoffJitter();
    double factor = 1.0 + (ThreadLocalRandom.current().nextDouble() * 2 - 1) * jitter;
    long delayMs = Math.max(0, (long) (raw * factor));
    return now.plusMillis(delayMs);
  }
}
