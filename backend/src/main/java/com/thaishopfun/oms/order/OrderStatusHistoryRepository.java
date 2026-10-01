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
      UUID orderId, String dimension, String fromValue, String toValue, String reason, String actor) {
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
}
