package com.thaishopfun.oms.order.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.thaishopfun.oms.auth.AuthTestSupport;
import com.thaishopfun.oms.channel.*;
import com.thaishopfun.oms.channel.api.PaymentStatus;
import com.thaishopfun.oms.inbox.*;
import com.thaishopfun.oms.order.*;
import com.thaishopfun.oms.order.payment.PaymentSnapshotService;
import com.thaishopfun.oms.tenant.TenantContext;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;

@org.springframework.test.context.ActiveProfiles("test")
@org.springframework.boot.test.context.SpringBootTest(
    webEnvironment =
        org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "oms.outbox.publisher-enabled=false")
@org.springframework.test.annotation.DirtiesContext
@com.thaishopfun.oms.invariant.VerifyInvariants
class PaymentSnapshotIntegrationTest {
  @org.springframework.boot.test.web.server.LocalServerPort int port;
  OrderHttp http;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    AuthTestSupport.register(registry);
    registry.add("oms.inbox.worker-enabled", () -> "true");
    registry.add("oms.inbox.worker-delay-ms", () -> "3600000");
    registry.add("oms.order.hold-resolver.enabled", () -> "true");
    registry.add("oms.order.hold-resolver.interval", () -> "PT1H");
    registry.add("oms.inbox.hmac-secrets", () -> "payment-test-secret");
  }

  @Autowired SalesOrderRepository orders;
  @Autowired OrderRecipientRepository recipients;
  @Autowired OrderStatusHistoryRepository history;
  @Autowired PlatformTransactionManager transactions;
  @Autowired PaymentSnapshotService payments;
  @Autowired InboxIngestService ingest;
  @Autowired InboxWorker worker;
  @Autowired JdbcTemplate jdbc;
  @Autowired JsonMapper json;
  @MockitoBean ChannelAdapterRegistry adapters;
  OrderFixture fixture;
  OrderFixture.Shop shop;
  com.thaishopfun.oms.catalog.CatalogHttp.Shop httpShop;
  SalesOrder order;

  @BeforeEach
  void setupPayment() throws Exception {
    http = new OrderHttp(port);
    fixture = new OrderFixture(orders, recipients, history, transactions);
    httpShop = http.catalog().shop();
    shop = OrderFixture.shopFor(httpShop);
    order = fixture.insert(shop, "PAY-" + UUID.randomUUID(), "UNFULFILLED", "NONE");
    fixture.inTenant(
        shop.tenantId(),
        () -> jdbc.update("UPDATE sales_order SET grand_total = 580 WHERE id = ?", order.id()));
  }

  @Test
  void inboxDuplicatePartialFullAndOldEvents() throws Exception {
    byte[] paid =
        event(
            "payment.status_changed",
            "pay-" + order.id(),
            1,
            "2026-09-29T08:15:02Z",
            Map.of("status", "PAID", "provider", "XENDIT"));
    assertThat(receive(paid).httpStatus()).isEqualTo(202);
    assertThat(receive(paid).httpStatus()).isEqualTo(200);
    worker.processAvailable();
    // Replay handler too: the persistence boundary deduplicates source_event_id independently.
    fixture.inTenant(
        shop.tenantId(),
        () -> {
          payments.handle(message(paid));
          payments.handle(message(paid));
          return null;
        });
    assertThat(count("payment_status_snapshot")).isEqualTo(1);
    deliverRefund("r1", "SUCCEEDED", "295", "2026-09-29T08:16:02Z", 9);
    assertThat(status()).isEqualTo("PARTIALLY_REFUNDED");
    deliverRefund("r1", "PENDING", "295", "2026-09-29T08:15:02Z", 2);
    assertThat(count("refund")).isEqualTo(1);
    assertThat(status()).isEqualTo("PARTIALLY_REFUNDED");
    deliverRefund("r2", "SUCCEEDED", "285", "2026-09-29T08:17:02Z", 1);
    assertThat(status()).isEqualTo("REFUNDED");
    byte[] old =
        event(
            "payment.status_changed",
            "pay-" + order.id(),
            2,
            "2026-09-29T08:14:02Z",
            Map.of("status", "PENDING", "provider", "OPN", "provider_ref", "chrg_old"));
    receive(old);
    worker.processAvailable();
    assertThat(status()).isEqualTo("REFUNDED");
    assertThat(count("payment_status_snapshot")).isEqualTo(2);
    assertThat(
            fixture.inTenant(
                shop.tenantId(),
                () ->
                    jdbc.queryForObject(
                        "SELECT count(*) FROM payment_status_snapshot WHERE order_id = ? AND provider = 'OPN'",
                        Integer.class,
                        order.id())))
        .isEqualTo(1);
    var detail = http.get(OrderHttp.ordersPath("/" + order.id()), httpShop.owner());
    assertThat(detail.status()).isEqualTo(200);
    assertThat(detail.body().path("payment_snapshot").path("status").asString()).isEqualTo("PAID");
    assertThat(detail.body().path("refunds").size()).isEqualTo(2);
    assertThat(detail.body().path("refunds").toString())
        .doesNotContain("provider_ref", "card", "account");
  }

  @Test
  void pendingAndFailedDoNotCountAndSuccessUpdatesTheSameRefund() throws Exception {
    deliverRefund("r1", "PENDING", "295", "2026-09-29T08:15:02Z", 1);
    assertThat(status()).isEqualTo("PAID");
    deliverRefund("r1", "FAILED", "295", "2026-09-29T08:16:02Z", 2);
    assertThat(status()).isEqualTo("PAID");
    deliverRefund("r1", "SUCCEEDED", "295", "2026-09-29T08:17:02Z", 3);
    assertThat(status()).isEqualTo("PARTIALLY_REFUNDED");
    assertThat(count("refund")).isEqualTo(1);
    byte[] latePaid =
        event("order.paid", order.externalOrderId(), 1, "2026-09-29T08:14:02Z", Map.of());
    receive(latePaid);
    worker.processAvailable();
    assertThat(status()).isEqualTo("PARTIALLY_REFUNDED");
    var otherShop = http.catalog().shop();
    assertThat(http.get(OrderHttp.ordersPath("/" + order.id()), otherShop.owner()).status())
        .isEqualTo(404);
  }

  @Test
  void fallbackCallsChannelGetAndRecordsOnlyChangedSnapshot() {
    var adapter = mock(ChannelAdapter.class);
    var ref = new ChannelAccountRef(shop.tenantId(), shop.channelAccountId(), shop.tsfShopId());
    when(adapters.require(Channel.TSF)).thenReturn(adapter);
    when(adapter.getPaymentStatus(ref, order.externalOrderId()))
        .thenReturn(
            new PaymentStatus(
                order.externalOrderId(),
                "PAID",
                List.of(
                    new PaymentStatus.Refund(
                        "r1", null, "SUCCEEDED", new BigDecimal("295"), "THB")),
                "XENDIT",
                "xnd_payment"));
    TenantContext.set(shop.tenantId(), null);
    try {
      assertThat(payments.reconcile(ref, order.externalOrderId())).isTrue();
      assertThat(payments.reconcile(ref, order.externalOrderId())).isFalse();
    } finally {
      TenantContext.clear();
    }
    verify(adapter, times(2)).getPaymentStatus(ref, order.externalOrderId());
    assertThat(count("payment_status_snapshot")).isEqualTo(1);
    assertThat(count("refund")).isEqualTo(1);
    assertThat(status()).isEqualTo("PARTIALLY_REFUNDED");
  }

  @Test
  void unknownProviderSkipsSnapshotButProjectsAndAudits() throws Exception {
    fixture.inTenant(
        shop.tenantId(),
        () ->
            jdbc.update(
                "UPDATE sales_order SET payment_status = 'PENDING' WHERE id = ?", order.id()));
    byte[] body =
        event(
            "payment.status_changed",
            "pay-" + order.id(),
            1,
            "2026-09-29T08:15:02Z",
            Map.of("status", "PAID"));
    assertThat(receive(body).httpStatus()).isEqualTo(202);
    assertThat(receive(body).httpStatus()).isEqualTo(200);
    worker.processAvailable();
    assertThat(status()).isEqualTo("PAID");
    assertThat(count("payment_status_snapshot")).isZero();
    assertThat(
            fixture.inTenant(
                shop.tenantId(),
                () ->
                    jdbc.queryForObject(
                        "SELECT count(*) FROM audit_log WHERE entity_id = ? AND action = 'PAYMENT_SNAPSHOT_RECORDED'",
                        Integer.class,
                        order.id().toString())))
        .isEqualTo(1);
  }

  @Test
  void unknownProviderFallbackStillProjectsRefundsAndAuditsWithoutSnapshot() {
    var adapter = mock(ChannelAdapter.class);
    var ref = new ChannelAccountRef(shop.tenantId(), shop.channelAccountId(), shop.tsfShopId());
    when(adapters.require(Channel.TSF)).thenReturn(adapter);
    when(adapter.getPaymentStatus(ref, order.externalOrderId()))
        .thenReturn(
            new PaymentStatus(
                order.externalOrderId(),
                "PAID",
                List.of(
                    new PaymentStatus.Refund(
                        "r1", null, "SUCCEEDED", new BigDecimal("295"), "THB"))));
    TenantContext.set(shop.tenantId(), null);
    try {
      assertThat(payments.reconcile(ref, order.externalOrderId())).isTrue();
    } finally {
      TenantContext.clear();
    }
    assertThat(status()).isEqualTo("PARTIALLY_REFUNDED");
    assertThat(count("payment_status_snapshot")).isZero();
    assertThat(
            fixture.inTenant(
                shop.tenantId(),
                () ->
                    jdbc.queryForObject(
                        "SELECT count(*) FROM audit_log WHERE entity_id = ? AND action = 'PAYMENT_SNAPSHOT_RECORDED'",
                        Integer.class,
                        order.id().toString())))
        .isEqualTo(1);
  }

  @Test
  void unknownProviderProjectionAndAuditRollBackTogether() {
    fixture.inTenant(
        shop.tenantId(),
        () ->
            jdbc.update(
                "UPDATE sales_order SET payment_status = 'PENDING' WHERE id = ?", order.id()));
    byte[] body =
        event(
            "payment.status_changed",
            "pay-" + order.id(),
            1,
            "2026-09-29T08:15:02Z",
            Map.of("status", "PAID"));
    assertThatThrownBy(
            () ->
                fixture.inTenant(
                    shop.tenantId(),
                    () -> {
                      payments.handle(message(body));
                      throw new IllegalStateException("rollback");
                    }))
        .isInstanceOf(IllegalStateException.class);
    assertThat(status()).isEqualTo("PENDING");
    assertThat(count("payment_status_snapshot")).isZero();
    assertThat(
            fixture.inTenant(
                shop.tenantId(),
                () ->
                    jdbc.queryForObject(
                        "SELECT count(*) FROM audit_log WHERE entity_id = ? AND action = 'PAYMENT_SNAPSHOT_RECORDED'",
                        Integer.class,
                        order.id().toString())))
        .isZero();
  }

  @Test
  void failedTransactionRollsBackSnapshotAndAudit() throws Exception {
    byte[] paid =
        event(
            "payment.status_changed",
            "pay-" + order.id(),
            1,
            "2026-09-29T08:15:02Z",
            Map.of("status", "PAID", "provider", "XENDIT"));
    assertThatThrownBy(
            () ->
                fixture.inTenant(
                    shop.tenantId(),
                    () -> {
                      payments.handle(message(paid));
                      throw new IllegalStateException("rollback");
                    }))
        .isInstanceOf(IllegalStateException.class);
    assertThat(count("payment_status_snapshot")).isZero();
    assertThat(
            fixture.inTenant(
                shop.tenantId(),
                () ->
                    jdbc.queryForObject(
                        "SELECT count(*) FROM audit_log WHERE entity_id = ? AND action = 'PAYMENT_SNAPSHOT_RECORDED'",
                        Integer.class,
                        order.id().toString())))
        .isZero();
  }

  void deliverRefund(String ref, String state, String amount, String at, long version)
      throws Exception {
    byte[] body =
        event(
            "refund.status_changed",
            ref + order.id(),
            version,
            at,
            Map.of(
                "refund_id",
                ref,
                "status",
                state,
                "amount",
                new BigDecimal(amount),
                "currency",
                "THB"));
    assertThat(receive(body).httpStatus()).isEqualTo(202);
    assertThat(receive(body).httpStatus()).isEqualTo(200);
    worker.processAvailable();
  }

  byte[] event(String type, String aggregate, long version, String at, Map<String, Object> fields) {
    var data = new java.util.HashMap<>(fields);
    data.put("order_id", order.externalOrderId());
    return json.writeValueAsBytes(
        Map.of(
            "event_id",
            UUID.randomUUID().toString(),
            "event_type",
            type,
            "schema_version",
            1,
            "occurred_at",
            at,
            "tsf_shop_id",
            shop.tsfShopId(),
            "aggregate_id",
            aggregate,
            "aggregate_version",
            version,
            "data",
            data));
  }

  InboxMessage message(byte[] body) {
    var node = json.readTree(body);
    return new InboxMessage(
        UUID.randomUUID(),
        shop.tenantId(),
        "TSF",
        node.path("event_id").asString(),
        node.path("event_type").asString(),
        node.path("aggregate_id").asString(),
        node.path("aggregate_version").asLong(),
        false,
        node);
  }

  InboxIngestService.IngestResult receive(byte[] body) throws Exception {
    String ts = Long.toString(Instant.now().getEpochSecond());
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(
        new SecretKeySpec(
            "payment-test-secret".getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
    mac.update((ts + ".").getBytes(java.nio.charset.StandardCharsets.UTF_8));
    var request = new MockHttpServletRequest();
    request.setContent(body);
    request.addHeader("X-Event-Id", json.readTree(body).path("event_id").asString());
    request.addHeader(
        "X-Signature", "t=" + ts + ",v1=" + HexFormat.of().formatHex(mac.doFinal(body)));
    return ingest.receive(request);
  }

  int count(String table) {
    return fixture.inTenant(
        shop.tenantId(),
        () ->
            jdbc.queryForObject(
                "SELECT count(*) FROM " + table + " WHERE order_id = ?",
                Integer.class,
                order.id()));
  }

  String status() {
    return fixture.inTenant(
        shop.tenantId(), () -> orders.findById(order.id()).orElseThrow().paymentStatus());
  }
}
