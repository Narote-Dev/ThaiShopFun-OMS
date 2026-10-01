package com.thaishopfun.oms.order;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class OrderLineRepository {

  public record OrderLine(
      UUID id,
      UUID orderId,
      UUID skuId,
      String externalLineId,
      String externalSkuId,
      String name,
      int qty,
      BigDecimal unitPrice) {}

  private final JdbcTemplate jdbc;

  public OrderLineRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public void insert(
      UUID id,
      UUID orderId,
      UUID skuId,
      String externalLineId,
      String externalSkuId,
      String name,
      int qty,
      BigDecimal unitPrice,
      BigDecimal discount,
      BigDecimal lineTotal) {
    jdbc.update(
        """
        INSERT INTO order_line (
          id, tenant_id, order_id, sku_id, external_line_id, external_sku_id, name, qty,
          unit_price, discount, line_total
        ) VALUES (
          ?, (SELECT tenant_id FROM sales_order WHERE id = ?), ?, ?, ?, ?, ?, ?, ?, ?, ?
        )
        """,
        id,
        orderId,
        orderId,
        skuId,
        externalLineId,
        externalSkuId,
        name,
        qty,
        unitPrice,
        discount,
        lineTotal);
  }

  public List<OrderLine> findByOrderId(UUID orderId) {
    return jdbc.query(
        """
        SELECT id, order_id, sku_id, external_line_id, external_sku_id, name, qty, unit_price
        FROM order_line WHERE order_id = ? ORDER BY external_line_id
        """,
        this::map,
        orderId);
  }

  private OrderLine map(ResultSet rs, int rowNum) throws SQLException {
    return new OrderLine(
        rs.getObject("id", UUID.class),
        rs.getObject("order_id", UUID.class),
        rs.getObject("sku_id", UUID.class),
        rs.getString("external_line_id"),
        rs.getString("external_sku_id"),
        rs.getString("name"),
        rs.getInt("qty"),
        rs.getBigDecimal("unit_price"));
  }
}
