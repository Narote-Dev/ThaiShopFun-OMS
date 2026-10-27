package com.thaishopfun.oms.order.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.thaishopfun.oms.channel.*;
import com.thaishopfun.oms.channel.api.PaymentStatus;
import com.thaishopfun.oms.inbox.*;
import com.thaishopfun.oms.order.*;
import com.thaishopfun.oms.tenant.TenantContext;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

class UnknownPaymentProviderTest {
  private final UUID tenant = UUID.randomUUID();
  private final UUID account = UUID.randomUUID();
  private final UUID orderId = UUID.randomUUID();
  private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
  private final SalesOrderRepository orders = mock(SalesOrderRepository.class);
  private final ChannelAccountLookup channels = mock(ChannelAccountLookup.class);
  private final ChannelAdapterRegistry adapters = mock(ChannelAdapterRegistry.class);
  private final OrderStatusHistoryRepository history = mock(OrderStatusHistoryRepository.class);
  private final SalesOrder order = mock(SalesOrder.class);
  private final PaymentSnapshotService service =
      new PaymentSnapshotService(
          jdbc,
          orders,
          channels,
          adapters,
          mock(InboxAggregateLock.class),
          new InboxProperties(),
          history,
          Clock.systemUTC());

  @BeforeEach
  void setup() {
    TenantContext.set(tenant, null);
    when(order.id()).thenReturn(orderId);
    when(order.tenantId()).thenReturn(tenant);
    when(order.grandTotal()).thenReturn(new BigDecimal("580"));
    when(order.currency()).thenReturn("THB");
    when(order.paymentStatus()).thenReturn("PENDING");
    when(orders.findByExternalId(account, "TSF-1")).thenReturn(Optional.of(order));
    when(orders.findById(orderId)).thenReturn(Optional.of(order));
    when(channels.tsfByExternalShopId("shop"))
        .thenReturn(
            Optional.of(new ChannelAccountLookup.TsfAccount(account, "ACTIVE", "CONNECTED")));
    when(jdbc.queryForObject(anyString(), eq(BigDecimal.class), eq(orderId)))
        .thenReturn(BigDecimal.ZERO);
  }

  @AfterEach
  void cleanup() {
    TenantContext.clear();
  }

  @Test
  void unknownProviderEventPersistsPaymentAndAuditWithoutSnapshot() {
    var payload =
        new JsonMapper()
            .valueToTree(
                Map.of(
                    "tsf_shop_id",
                    "shop",
                    "occurred_at",
                    "2026-09-29T08:15:02Z",
                    "data",
                    Map.of("order_id", "TSF-1", "status", "PAID")));
    service.handle(
        new InboxMessage(
            UUID.randomUUID(),
            tenant,
            "TSF",
            "event-1",
            "payment.status_changed",
            "pay-1",
            1,
            false,
            payload));
    verify(jdbc).update(contains("UPDATE sales_order SET payment_status"), eq("PAID"), eq(orderId));
    verify(jdbc).update(contains("INSERT INTO audit_log"), any(Object[].class));
    verify(jdbc, never())
        .update(contains("INSERT INTO payment_status_snapshot"), any(Object[].class));
    verify(history).append(orderId, "PAYMENT", "PENDING", "PAID", "TSF_PAYMENT_SNAPSHOT", "SYSTEM");
  }

  @Test
  void codUnpaidFallbackAuditsEvenWithoutSnapshotOrStatusTransition() {
    when(order.paymentStatus()).thenReturn("COD_PENDING");
    var adapter = mock(ChannelAdapter.class);
    var ref = new ChannelAccountRef(tenant, account, "shop");
    when(adapters.require(Channel.TSF)).thenReturn(adapter);
    when(adapter.getPaymentStatus(ref, "TSF-1"))
        .thenReturn(new PaymentStatus("TSF-1", "UNPAID", List.of()));
    assertThat(service.reconcile(ref, "TSF-1")).isFalse();
    verify(jdbc).update(contains("INSERT INTO audit_log"), any(Object[].class));
    verify(jdbc, never())
        .update(contains("INSERT INTO payment_status_snapshot"), any(Object[].class));
    verify(jdbc, never())
        .update(contains("UPDATE sales_order SET payment_status"), any(Object[].class));
  }
}
