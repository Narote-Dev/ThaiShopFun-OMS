package com.thaishopfun.oms.order;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Plain JDBC over {@code sales_order}. Just enough for T10's schema tests; intake, the state
 * machine, and inbox handlers are T12. Queries run under the caller's transaction and tenant, so
 * RLS scopes them without a tenant filter.
 */
@Repository
public class SalesOrderRepository {

  private static final String COLUMNS =
      "id, tenant_id, channel_account_id, external_order_id, order_status, payment_status, "
          + "fulfillment_status, hold_reason, channel_status, payment_method, currency, subtotal, "
          + "shipping_fee, discount, grand_total, ordered_at, paid_at, ship_by, external_version, "
          + "version";

  private final JdbcTemplate jdbc;

  public SalesOrderRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public void insert(SalesOrder order) {
    // Step 1: One row. The unique key (channel_account_id, external_order_id) rejects a duplicate.
    jdbc.update(
        "INSERT INTO sales_order ("
            + COLUMNS
            + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        order.id(),
        order.tenantId(),
        order.channelAccountId(),
        order.externalOrderId(),
        order.orderStatus(),
        order.paymentStatus(),
        order.fulfillmentStatus(),
        order.holdReason(),
        order.channelStatus(),
        order.paymentMethod(),
        order.currency(),
        order.subtotal(),
        order.shippingFee(),
        order.discount(),
        order.grandTotal(),
        timestamp(order.orderedAt()),
        timestamp(order.paidAt()),
        timestamp(order.shipBy()),
        order.externalVersion(),
        order.version());
  }

  public Optional<SalesOrder> findById(UUID id) {
    return first(jdbc.query("SELECT " + COLUMNS + " FROM sales_order WHERE id = ?", this::map, id));
  }

  public Optional<SalesOrder> findByExternalId(UUID channelAccountId, String externalOrderId) {
    return first(
        jdbc.query(
            "SELECT "
                + COLUMNS
                + " FROM sales_order WHERE channel_account_id = ? AND external_order_id = ?",
            this::map,
            channelAccountId,
            externalOrderId));
  }

  private SalesOrder map(ResultSet rows, int rowNum) throws SQLException {
    return new SalesOrder(
        rows.getObject("id", UUID.class),
        rows.getObject("tenant_id", UUID.class),
        rows.getObject("channel_account_id", UUID.class),
        rows.getString("external_order_id"),
        rows.getString("order_status"),
        rows.getString("payment_status"),
        rows.getString("fulfillment_status"),
        rows.getString("hold_reason"),
        rows.getString("channel_status"),
        rows.getString("payment_method"),
        rows.getString("currency"),
        rows.getBigDecimal("subtotal"),
        rows.getBigDecimal("shipping_fee"),
        rows.getBigDecimal("discount"),
        rows.getBigDecimal("grand_total"),
        instant(rows.getTimestamp("ordered_at")),
        instant(rows.getTimestamp("paid_at")),
        instant(rows.getTimestamp("ship_by")),
        rows.getObject("external_version", Long.class),
        rows.getLong("version"));
  }

  private static <T> Optional<T> first(List<T> rows) {
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
  }

  static Timestamp timestamp(Instant instant) {
    return instant == null ? null : Timestamp.from(instant);
  }

  static Instant instant(Timestamp timestamp) {
    return timestamp == null ? null : timestamp.toInstant();
  }
}
