package com.thaishopfun.oms.order.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.thaishopfun.oms.auth.UuidV7;
import com.thaishopfun.oms.catalog.CatalogHttp;
import com.thaishopfun.oms.order.SalesOrder;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

class OrderApiBundleTest extends OrderIntegrationTest {

  @Autowired JdbcTemplate jdbc;
  @Autowired PlatformTransactionManager transactions;
  @Autowired com.thaishopfun.oms.order.SalesOrderRepository orders;
  @Autowired com.thaishopfun.oms.order.OrderRecipientRepository recipients;
  @Autowired com.thaishopfun.oms.order.OrderStatusHistoryRepository history;

  private OrderFixture fixture;

  @BeforeEach
  void setup() {
    fixture = new OrderFixture(orders, recipients, history, transactions);
  }

  @Test
  void bundleLineDetailIncludesComponents() throws Exception {
    CatalogHttp.Shop httpShop = http.catalog().shop();
    OrderFixture.Shop shop = OrderFixture.shopFor(httpShop);
    UUID product = UuidV7.generate();
    UUID component = UuidV7.generate();
    UUID bundle = UuidV7.generate();
    UUID orderId = UuidV7.generate();
    fixture.inTenant(
        shop.tenantId(),
        () -> {
          jdbc.update(
              "INSERT INTO product (id, tenant_id, name, status) VALUES (?, ?, 'P', 'ACTIVE')",
              product,
              shop.tenantId());
          jdbc.update(
              "INSERT INTO sku (id, tenant_id, product_id, sku_code, name, is_bundle) VALUES (?, ?, ?, 'C', 'Comp', false)",
              component,
              shop.tenantId(),
              product);
          jdbc.update(
              "INSERT INTO sku (id, tenant_id, product_id, sku_code, name, is_bundle) VALUES (?, ?, ?, 'B', 'Bundle', true)",
              bundle,
              shop.tenantId(),
              product);
          jdbc.update(
              "INSERT INTO sku_bundle_component (tenant_id, bundle_sku_id, component_sku_id, qty) VALUES (?, ?, ?, 2)",
              shop.tenantId(),
              bundle,
              component);
          SalesOrder order =
              new SalesOrder(
                  orderId,
                  shop.tenantId(),
                  shop.channelAccountId(),
                  "ORD-BUNDLE",
                  "ACTIVE",
                  "PAID",
                  "READY_TO_PICK",
                  "NONE",
                  null,
                  null,
                  "PREPAID",
                  "THB",
                  java.math.BigDecimal.TEN,
                  java.math.BigDecimal.ZERO,
                  java.math.BigDecimal.ZERO,
                  java.math.BigDecimal.TEN,
                  java.time.Instant.now(),
                  java.time.Instant.now(),
                  null,
                  1L,
                  0);
          orders.insert(order);
          jdbc.update(
              """
              INSERT INTO order_line (id, tenant_id, order_id, sku_id, external_line_id, external_sku_id, name, qty, unit_price)
              VALUES (?, ?, ?, ?, 'L1', 'ext', 'Line', 1, 10)
              """,
              UuidV7.generate(),
              shop.tenantId(),
              orderId,
              bundle);
          return null;
        });
    CatalogHttp.Result detail = http.get(OrderHttp.ordersPath("/" + orderId), httpShop.owner());
    assertThat(detail.status()).isEqualTo(200);
    assertThat(detail.body().path("lines").get(0).path("bundle").asBoolean()).isTrue();
    assertThat(detail.body().path("lines").get(0).path("components")).hasSize(1);
    assertThat(
            detail
                .body()
                .path("lines")
                .get(0)
                .path("components")
                .get(0)
                .path("sku_code")
                .asString())
        .isEqualTo("C");
  }
}
