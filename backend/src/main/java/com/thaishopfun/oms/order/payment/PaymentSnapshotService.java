package com.thaishopfun.oms.order.payment;

import com.thaishopfun.oms.auth.UuidV7;
import com.thaishopfun.oms.channel.*;
import com.thaishopfun.oms.channel.api.PaymentStatus;
import com.thaishopfun.oms.inbox.*;
import com.thaishopfun.oms.order.*;
import com.thaishopfun.oms.tenant.TenantContext;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * All writes, projection and audit share the tenant transaction. Payloads are never persisted here.
 */
@Service
public class PaymentSnapshotService {
  private final JdbcTemplate jdbc;
  private final SalesOrderRepository orders;
  private final ChannelAccountLookup channels;
  private final ChannelAdapterRegistry adapters;
  private final InboxAggregateLock locks;
  private final InboxProperties properties;
  private final OrderStatusHistoryRepository history;
  private final Clock clock;

  public PaymentSnapshotService(
      JdbcTemplate jdbc,
      SalesOrderRepository orders,
      ChannelAccountLookup channels,
      ChannelAdapterRegistry adapters,
      InboxAggregateLock locks,
      InboxProperties properties,
      OrderStatusHistoryRepository history,
      Clock clock) {
    this.jdbc = jdbc;
    this.orders = orders;
    this.channels = channels;
    this.adapters = adapters;
    this.locks = locks;
    this.properties = properties;
    this.history = history;
    this.clock = clock;
  }

  @Transactional
  public void handle(InboxMessage message) {
    JsonNode data = message.payload().path("data");
    var account =
        channels
            .tsfByExternalShopId(message.payload().path("tsf_shop_id").asString())
            .orElseThrow(() -> new InboxDeferException(properties.getDeferDelay()));
    SalesOrder order = lock(account.id(), data.path("order_id").asString());
    Instant observed = Instant.parse(message.payload().path("occurred_at").asString());
    if ("payment.status_changed".equals(message.eventType())) {
      String status = data.path("status").asString();
      validateStatus(status);
      String providerRef = data.path("provider_ref").asString(null);
      String provider = PaymentProvider.resolve(data.path("provider").asString(null), providerRef);
      if (provider == null) {
        project(
            order,
            new Snapshot(
                status,
                order.grandTotal(),
                BigDecimal.ZERO,
                order.currency(),
                order.paidAt(),
                observed));
        audit(order);
      } else if (snapshot(
          order, status, BigDecimal.ZERO, observed, message.eventId(), provider, providerRef)) {
        project(order, null);
        audit(order);
      }
    } else if (refund(
        order,
        data.path("refund_id").asString(),
        data.path("return_id").asString(null),
        data.path("status").asString(),
        new BigDecimal(data.path("amount").asString()),
        data.path("currency").asString(),
        observed,
        message.eventId(),
        false)) {
      project(order, null);
      audit(order);
    }
  }

  /**
   * Reconciliation entry point. Caller establishes tenant context; adapter owns the GET contract.
   */
  @Transactional
  public boolean reconcile(ChannelAccountRef account, String externalOrderId) {
    if (!TenantContext.requireTenantId().equals(account.tenantId())) {
      throw new IllegalArgumentException("tenant mismatch");
    }
    SalesOrder order = lock(account.channelAccountId(), externalOrderId);
    PaymentStatus payment =
        adapters.require(Channel.TSF).getPaymentStatus(account, externalOrderId);
    if (!externalOrderId.equals(payment.orderId()))
      throw new IllegalArgumentException("order mismatch");
    validateStatus(payment.status());
    String provider = PaymentProvider.resolve(payment.provider(), payment.providerRef());
    Instant observed = clock.instant();
    boolean changed = false;
    for (PaymentStatus.Refund refund :
        payment.refunds() == null ? List.<PaymentStatus.Refund>of() : payment.refunds()) {
      changed |=
          refund(
              order,
              refund.refundId(),
              refund.returnId(),
              refund.status(),
              refund.amount(),
              refund.currency(),
              observed,
              "reconcile:" + UuidV7.generate(),
              true);
    }
    BigDecimal refunded = succeeded(order.id());
    Snapshot latest = latest(order.id());
    if (provider != null
        && (latest == null
            || !latest.status().equals(payment.status())
            || latest.refundedAmount().compareTo(refunded) != 0)) {
      changed |=
          snapshot(
              order,
              payment.status(),
              refunded,
              observed,
              "reconcile:" + UuidV7.generate(),
              provider,
              payment.providerRef());
    }
    if (provider == null) {
      changed |=
          project(
              order,
              new Snapshot(
                  payment.status(),
                  order.grandTotal(),
                  refunded,
                  order.currency(),
                  order.paidAt(),
                  observed));
      audit(order);
    } else if (changed) {
      project(order, null);
      audit(order);
    }
    return changed;
  }

  private SalesOrder lock(UUID account, String external) {
    locks.lockOrder(jdbc, TenantContext.requireTenantId(), external);
    var order =
        orders
            .findByExternalId(account, external)
            .orElseThrow(() -> new InboxDeferException(properties.getDeferDelay()));
    jdbc.queryForObject(
        "SELECT id FROM sales_order WHERE id = ? FOR UPDATE", UUID.class, order.id());
    return orders.findById(order.id()).orElseThrow();
  }

  private static void validateStatus(String status) {
    if (status == null
        || !List.of("UNPAID", "PENDING", "PAID", "PARTIALLY_REFUNDED", "REFUNDED", "FAILED")
            .contains(status)) {
      throw new NonRetryableInboxException("invalid payment status");
    }
  }

  private boolean snapshot(
      SalesOrder order,
      String status,
      BigDecimal refunded,
      Instant at,
      String source,
      String provider,
      String providerRef) {
    return jdbc.update(
            """
        INSERT INTO payment_status_snapshot
          (id, tenant_id, order_id, provider, provider_ref, status, amount, refunded_amount, currency, paid_at, observed_at, source_event_id)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT (tenant_id, source_event_id) DO NOTHING
        """,
            UuidV7.generate(),
            order.tenantId(),
            order.id(),
            provider,
            providerRef,
            status,
            order.grandTotal(),
            refunded,
            order.currency(),
            order.paidAt() == null ? null : Timestamp.from(order.paidAt()),
            Timestamp.from(at),
            source)
        > 0;
  }

  private boolean refund(
      SalesOrder order,
      String ref,
      String returnRef,
      String status,
      BigDecimal amount,
      String currency,
      Instant at,
      String source,
      boolean fallback) {
    if (ref == null
        || ref.isBlank()
        || !List.of("PENDING", "SUCCEEDED", "FAILED").contains(status)
        || amount == null
        || amount.signum() < 0
        || !order.currency().equals(currency)) {
      throw new NonRetryableInboxException("invalid refund fields");
    }
    if (Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM refund WHERE tenant_id = ? AND source_event_id = ?)",
            Boolean.class,
            order.tenantId(),
            source))) return false;
    var existing =
        jdbc.query(
            "SELECT id, status, amount, observed_at FROM refund WHERE order_id = ? AND provider_ref = ?",
            (rs, n) ->
                new RefundRow(
                    rs.getObject("id", UUID.class),
                    rs.getString("status"),
                    rs.getBigDecimal("amount"),
                    rs.getTimestamp("observed_at").toInstant()),
            order.id(),
            ref);
    if (!existing.isEmpty()) {
      var old = existing.get(0);
      // Terminal success is sticky. Equal-time deliveries cannot regress a terminal state.
      if (at.isBefore(old.at())
          || "SUCCEEDED".equals(old.status())
          || (at.equals(old.at())
              && !"SUCCEEDED".equals(status)
              && (!"PENDING".equals(old.status()) || "PENDING".equals(status)))
          || (fallback && old.status().equals(status) && old.amount().compareTo(amount) == 0))
        return false;
      return jdbc.update(
              "UPDATE refund SET status = ?, amount = ?, observed_at = ?, updated_at = now() WHERE id = ?",
              status,
              amount,
              Timestamp.from(at),
              old.id())
          > 0;
    }
    UUID returnId =
        jdbc.query(
            "SELECT id FROM return_request WHERE order_id = ? AND external_return_id = ?",
            rs -> rs.next() ? rs.getObject("id", UUID.class) : null,
            order.id(),
            returnRef);
    return jdbc.update(
            """
        INSERT INTO refund (id, tenant_id, order_id, return_id, provider_ref, amount, currency, status, observed_at, source_event_id)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (tenant_id, source_event_id) DO NOTHING
        """,
            UuidV7.generate(),
            order.tenantId(),
            order.id(),
            returnId,
            ref,
            amount,
            currency,
            status,
            Timestamp.from(at),
            source)
        > 0;
  }

  public Snapshot latest(UUID orderId) {
    return jdbc.query(
        """
        SELECT status, amount, refunded_amount, currency, paid_at, observed_at FROM payment_status_snapshot
        WHERE order_id = ? ORDER BY observed_at DESC,
          CASE status WHEN 'REFUNDED' THEN 3 WHEN 'PARTIALLY_REFUNDED' THEN 2 WHEN 'PAID' THEN 1 ELSE 0 END DESC,
          source_event_id DESC LIMIT 1
        """,
        rs ->
            rs.next()
                ? new Snapshot(
                    rs.getString("status"),
                    rs.getBigDecimal("amount"),
                    rs.getBigDecimal("refunded_amount"),
                    rs.getString("currency"),
                    rs.getTimestamp("paid_at") == null
                        ? null
                        : rs.getTimestamp("paid_at").toInstant(),
                    rs.getTimestamp("observed_at").toInstant())
                : null,
        orderId);
  }

  private BigDecimal succeeded(UUID orderId) {
    return jdbc.queryForObject(
        "SELECT COALESCE(SUM(amount), 0) FROM refund WHERE order_id = ? AND status = 'SUCCEEDED'",
        BigDecimal.class,
        orderId);
  }

  private boolean project(SalesOrder order, Snapshot observation) {
    Snapshot snapshot = latest(order.id());
    if (observation != null
        && (snapshot == null || !observation.observedAt().isBefore(snapshot.observedAt()))) {
      snapshot = observation;
    }
    String next =
        PaymentProjection.status(
            snapshot == null ? null : snapshot.status(),
            order.paymentStatus(),
            order.grandTotal(),
            snapshot == null ? BigDecimal.ZERO : snapshot.refundedAmount(),
            succeeded(order.id()));
    if (!Objects.equals(next, order.paymentStatus())) {
      jdbc.update(
          "UPDATE sales_order SET payment_status = ?, version = version + 1, updated_at = now() WHERE id = ?",
          next,
          order.id());
      history.append(
          order.id(), "PAYMENT", order.paymentStatus(), next, "TSF_PAYMENT_SNAPSHOT", "SYSTEM");
      return true;
    }
    return false;
  }

  private void audit(SalesOrder order) {
    jdbc.update(
        """
        INSERT INTO audit_log (id, tenant_id, actor_type, actor_id, action, entity_type, entity_id)
        VALUES (?, ?, 'SYSTEM', 'payment-snapshot', 'PAYMENT_SNAPSHOT_RECORDED', 'sales_order', ?)
        """,
        UuidV7.generate(),
        order.tenantId(),
        order.id().toString());
  }

  public record Snapshot(
      String status,
      BigDecimal amount,
      BigDecimal refundedAmount,
      String currency,
      Instant paidAt,
      Instant observedAt) {}

  private record RefundRow(UUID id, String status, BigDecimal amount, Instant at) {}
}
