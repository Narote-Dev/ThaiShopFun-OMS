package com.thaishopfun.oms.fulfillment;

import com.thaishopfun.oms.tenant.TenantContext;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Clears pack scan progress and releases pick-list membership when an order is cancelled unpacked.
 */
@Component
public class FulfillmentCancelSupport {

  private final JdbcTemplate jdbc;

  public FulfillmentCancelSupport(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public void clearPackingState(UUID orderId) {
    UUID tenantId = TenantContext.requireTenantId();
    // Step 1: Drop per-line scan counts so a future order cannot inherit stale progress.
    jdbc.update(
        """
        DELETE FROM order_pack_progress
        WHERE tenant_id = ? AND order_id = ?
        """,
        tenantId,
        orderId);
    // Step 2: Release active pick-list membership so the order can join another list later.
    jdbc.update(
        """
        UPDATE pick_list_order
        SET released_at = now()
        WHERE tenant_id = ? AND order_id = ? AND released_at IS NULL
        """,
        tenantId,
        orderId);
  }
}
