package com.thaishopfun.oms.order;

import com.thaishopfun.oms.auth.UuidV7;
import com.thaishopfun.oms.tenant.TenantContext;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class OrderStatusHistoryRepository {

  private final JdbcTemplate jdbc;

  public OrderStatusHistoryRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public void append(
      UUID orderId,
      String dimension,
      String fromValue,
      String toValue,
      String reason,
      String actor) {
    UUID tenantId = TenantContext.requireTenantId();
    jdbc.update(
        """
        INSERT INTO order_status_history (
          id, tenant_id, order_id, dimension, from_value, to_value, reason, actor
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """,
        UuidV7.generate(),
        tenantId,
        orderId,
        dimension,
        fromValue,
        toValue,
        reason,
        actor);
  }

  /** Latest time a dimension reached {@code toValue} (for COMPLETED guard). */
  public java.util.Optional<java.time.Instant> transitionedAt(
      UUID orderId, String dimension, String toValue) {
    return jdbc.query(
        """
        SELECT created_at FROM order_status_history
        WHERE order_id = ? AND dimension = ? AND to_value = ?
        ORDER BY created_at DESC
        LIMIT 1
        """,
        rs -> {
          if (!rs.next()) {
            return java.util.Optional.empty();
          }
          return java.util.Optional.of(
              rs.getObject("created_at", java.time.OffsetDateTime.class).toInstant());
        },
        orderId,
        dimension,
        toValue);
  }
}
