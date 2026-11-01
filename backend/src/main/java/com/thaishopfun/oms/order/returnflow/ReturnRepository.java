package com.thaishopfun.oms.order.returnflow;

import com.thaishopfun.oms.auth.UuidV7;
import com.thaishopfun.oms.tenant.TenantContext;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ReturnRepository {

  private final JdbcTemplate jdbc;

  public ReturnRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public record ReturnRequestRow(
      UUID id,
      UUID orderId,
      String externalReturnId,
      String type,
      String status,
      boolean rejected,
      String reason,
      Instant requestedAt,
      Instant receivedAt) {}

  record ReturnLineRow(
      UUID id,
      UUID orderId,
      UUID returnId,
      UUID orderLineId,
      String externalLineId,
      int qty,
      String condition,
      int restockedQty,
      UUID skuId) {}

  public Optional<ReturnRequestRow> findByExternalId(UUID orderId, String externalReturnId) {
    UUID tenantId = TenantContext.requireTenantId();
    return jdbc
        .query(
            """
            SELECT id, order_id, external_return_id, type, status, rejected, reason,
              requested_at, received_at
            FROM return_request
            WHERE tenant_id = ? AND order_id = ? AND external_return_id = ?
            """,
            (rs, rowNum) ->
                new ReturnRequestRow(
                    rs.getObject("id", UUID.class),
                    rs.getObject("order_id", UUID.class),
                    rs.getString("external_return_id"),
                    rs.getString("type"),
                    rs.getString("status"),
                    rs.getBoolean("rejected"),
                    rs.getString("reason"),
                    rs.getTimestamp("requested_at").toInstant(),
                    optionalInstant(rs.getTimestamp("received_at"))),
            tenantId,
            orderId,
            externalReturnId)
        .stream()
        .findFirst();
  }

  Optional<ReturnRequestRow> findById(UUID returnId) {
    UUID tenantId = TenantContext.requireTenantId();
    return jdbc
        .query(
            """
            SELECT id, order_id, external_return_id, type, status, rejected, reason,
              requested_at, received_at
            FROM return_request
            WHERE tenant_id = ? AND id = ?
            """,
            (rs, rowNum) ->
                new ReturnRequestRow(
                    rs.getObject("id", UUID.class),
                    rs.getObject("order_id", UUID.class),
                    rs.getString("external_return_id"),
                    rs.getString("type"),
                    rs.getString("status"),
                    rs.getBoolean("rejected"),
                    rs.getString("reason"),
                    rs.getTimestamp("requested_at").toInstant(),
                    optionalInstant(rs.getTimestamp("received_at"))),
            tenantId,
            returnId)
        .stream()
        .findFirst();
  }

  ReturnRequestRow insertRequest(
      UUID orderId, String externalReturnId, String type, String reason, Instant requestedAt) {
    UUID tenantId = TenantContext.requireTenantId();
    UUID id = UuidV7.generate();
    jdbc.update(
        """
        INSERT INTO return_request (
          id, tenant_id, order_id, external_return_id, type, status, reason, requested_at
        ) VALUES (?, ?, ?, ?, ?, 'REQUESTED', ?, ?)
        """,
        id,
        tenantId,
        orderId,
        externalReturnId,
        type,
        reason,
        Timestamp.from(requestedAt));
    return findById(id).orElseThrow();
  }

  void updateStatus(UUID returnId, String status) {
    jdbc.update(
        """
        UPDATE return_request
        SET status = ?, updated_at = now()
        WHERE tenant_id = ? AND id = ?
        """,
        status,
        TenantContext.requireTenantId(),
        returnId);
  }

  void markReceived(UUID returnId, Instant receivedAt) {
    jdbc.update(
        """
        UPDATE return_request
        SET status = 'RECEIVED', received_at = ?, updated_at = now()
        WHERE tenant_id = ? AND id = ?
        """,
        Timestamp.from(receivedAt),
        TenantContext.requireTenantId(),
        returnId);
  }

  Optional<ReturnLineRow> findLine(UUID returnId, UUID orderLineId) {
    UUID tenantId = TenantContext.requireTenantId();
    return jdbc
        .query(
            """
            SELECT rl.id, rl.order_id, rl.return_id, rl.order_line_id, ol.external_line_id,
              rl.qty, rl.condition, rl.restocked_qty, ol.sku_id
            FROM return_line rl
            JOIN order_line ol ON ol.tenant_id = rl.tenant_id AND ol.id = rl.order_line_id
            WHERE rl.tenant_id = ? AND rl.return_id = ? AND rl.order_line_id = ?
            """,
            (rs, rowNum) -> mapLine(rs),
            tenantId,
            returnId,
            orderLineId)
        .stream()
        .findFirst();
  }

  ReturnLineRow insertLine(UUID orderId, UUID returnId, UUID orderLineId, int qty) {
    UUID tenantId = TenantContext.requireTenantId();
    UUID id = UuidV7.generate();
    jdbc.update(
        """
        INSERT INTO return_line (id, tenant_id, order_id, return_id, order_line_id, qty)
        VALUES (?, ?, ?, ?, ?, ?)
        """,
        id,
        tenantId,
        orderId,
        returnId,
        orderLineId,
        qty);
    return findLine(returnId, orderLineId).orElseThrow();
  }

  List<ReturnLineRow> linesForReturn(UUID returnId) {
    UUID tenantId = TenantContext.requireTenantId();
    return jdbc.query(
        """
        SELECT rl.id, rl.order_id, rl.return_id, rl.order_line_id, ol.external_line_id,
          rl.qty, rl.condition, rl.restocked_qty, ol.sku_id
        FROM return_line rl
        JOIN order_line ol ON ol.tenant_id = rl.tenant_id AND ol.id = rl.order_line_id
        WHERE rl.tenant_id = ? AND rl.return_id = ?
        ORDER BY ol.external_line_id, rl.id
        """,
        (rs, rowNum) -> mapLine(rs),
        tenantId,
        returnId);
  }

  public List<ReturnRequestRow> openReturnsForOrder(UUID orderId) {
    UUID tenantId = TenantContext.requireTenantId();
    return jdbc.query(
        """
        SELECT id, order_id, external_return_id, type, status, rejected, reason,
          requested_at, received_at
        FROM return_request
        WHERE tenant_id = ? AND order_id = ?
          AND status IN ('REQUESTED', 'APPROVED', 'RECEIVED')
        ORDER BY requested_at, id
        """,
        (rs, rowNum) ->
            new ReturnRequestRow(
                rs.getObject("id", UUID.class),
                rs.getObject("order_id", UUID.class),
                rs.getString("external_return_id"),
                rs.getString("type"),
                rs.getString("status"),
                rs.getBoolean("rejected"),
                rs.getString("reason"),
                rs.getTimestamp("requested_at").toInstant(),
                optionalInstant(rs.getTimestamp("received_at"))),
        tenantId,
        orderId);
  }

  void updateLineCondition(UUID returnLineId, String condition, int restockedQty) {
    jdbc.update(
        """
        UPDATE return_line
        SET condition = ?, restocked_qty = ?, updated_at = now()
        WHERE tenant_id = ? AND id = ?
        """,
        condition,
        restockedQty,
        TenantContext.requireTenantId(),
        returnLineId);
  }

  Optional<UUID> orderLineIdByExternal(UUID orderId, String externalLineId) {
    UUID tenantId = TenantContext.requireTenantId();
    return jdbc
        .query(
            """
            SELECT id FROM order_line
            WHERE tenant_id = ? AND order_id = ? AND external_line_id = ?
            """,
            (rs, rowNum) -> rs.getObject("id", UUID.class),
            tenantId,
            orderId,
            externalLineId)
        .stream()
        .findFirst();
  }

  int shippedQtyForLine(UUID orderLineId, String fulfillmentStatus) {
    if (!"SHIPPED".equals(fulfillmentStatus) && !"DELIVERED".equals(fulfillmentStatus)) {
      return 0;
    }
    Integer qty =
        jdbc.queryForObject(
            """
            SELECT qty FROM order_line
            WHERE tenant_id = ? AND id = ?
            """,
            Integer.class,
            TenantContext.requireTenantId(),
            orderLineId);
    return qty == null ? 0 : qty;
  }

  private static ReturnLineRow mapLine(java.sql.ResultSet rs) throws java.sql.SQLException {
    return new ReturnLineRow(
        rs.getObject("id", UUID.class),
        rs.getObject("order_id", UUID.class),
        rs.getObject("return_id", UUID.class),
        rs.getObject("order_line_id", UUID.class),
        rs.getString("external_line_id"),
        rs.getInt("qty"),
        rs.getString("condition"),
        rs.getInt("restocked_qty"),
        rs.getObject("sku_id", UUID.class));
  }

  private static Instant optionalInstant(Timestamp value) {
    return value == null ? null : value.toInstant();
  }
}
