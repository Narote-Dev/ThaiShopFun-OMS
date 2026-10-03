package com.thaishopfun.oms.order;

import com.thaishopfun.oms.stock.ReserveDemandPlanner;
import com.thaishopfun.oms.stock.ReserveItem;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Checks ACTIVE ORDER reservations cover mapped order lines (component-level). */
@Component
public class OrderReservationCoverage {

  private final JdbcTemplate jdbc;
  private final ReserveDemandPlanner demandPlanner;

  public OrderReservationCoverage(JdbcTemplate jdbc, ReserveDemandPlanner demandPlanner) {
    this.jdbc = jdbc;
    this.demandPlanner = demandPlanner;
  }

  public boolean covers(UUID orderId, List<ReserveItem> mappedNeeds, Instant now) {
    if (mappedNeeds.isEmpty()) {
      return true;
    }
    ReserveDemandPlanner.ComponentDemandPlan plan = demandPlanner.componentDemandPlan(mappedNeeds);
    if (!plan.componentlessBundleSkuIds().isEmpty()) {
      return false;
    }
    Map<UUID, Integer> need = plan.demand();
    if (need.isEmpty()) {
      return false;
    }
    OffsetDateTime cutoff = OffsetDateTime.ofInstant(now, ZoneOffset.UTC);
    List<Map<String, Object>> rows =
        jdbc.queryForList(
            """
            SELECT sku_id, sum(qty) AS qty
            FROM stock_reservation
            WHERE owner_type = 'ORDER' AND owner_ref = ? AND status = 'ACTIVE'
              AND (expires_at IS NULL OR expires_at > ?)
            GROUP BY sku_id
            """,
            orderId.toString(),
            cutoff);
    Map<UUID, Integer> held = new HashMap<>();
    for (Map<String, Object> row : rows) {
      held.put((UUID) row.get("sku_id"), ((Number) row.get("qty")).intValue());
    }
    for (Map.Entry<UUID, Integer> entry : need.entrySet()) {
      if (held.getOrDefault(entry.getKey(), 0) < entry.getValue()) {
        return false;
      }
    }
    return true;
  }
}
