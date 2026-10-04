package com.thaishopfun.oms.order.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.thaishopfun.oms.auth.UuidV7;
import com.thaishopfun.oms.catalog.CatalogHttp;
import com.thaishopfun.oms.order.OrderRecipientRepository;
import com.thaishopfun.oms.order.OrderStatusHistoryRepository;
import com.thaishopfun.oms.order.SalesOrder;
import com.thaishopfun.oms.order.SalesOrderRepository;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.JsonNode;

class OrderApiHoldsTest extends OrderIntegrationTest {

  @Autowired SalesOrderRepository orders;
  @Autowired OrderRecipientRepository recipients;
  @Autowired OrderStatusHistoryRepository history;
  @Autowired PlatformTransactionManager transactions;
  @Autowired JdbcTemplate jdbc;

  private OrderFixture fixture;

  @BeforeEach
  void setup() {
    fixture = new OrderFixture(orders, recipients, history, transactions);
  }

  @Test
  void holdGroupsAndCounts() throws Exception {
    CatalogHttp.Shop httpShop = http.catalog().shop();
    OrderFixture.Shop shop = OrderFixture.shopFor(httpShop);
    UUID secondAccount = UuidV7.generate();
    fixture.inTenant(
        shop.tenantId(),
        () -> {
          jdbc.update(
              """
              INSERT INTO channel_account (id, tenant_id, channel, external_shop_id, status, mode)
              VALUES (?, ?, 'TSF', ?, 'CONNECTED', 'ACTIVE')
              """,
              secondAccount,
              shop.tenantId(),
              shop.tsfShopId() + "-alt");
          return null;
        });
    fixture.insert(shop, "H-NONE", "READY_TO_PICK", "NONE");
    fixture.insert(shop, "H-MAP", "UNFULFILLED", "SKU_NOT_MAPPED");
    fixture.insert(
        shop, "H-MAP-2", "ACTIVE", "PAID", "UNFULFILLED", "SKU_NOT_MAPPED", secondAccount);
    fixture.insert(shop, "H-OOS", "UNFULFILLED", "OUT_OF_STOCK");
    insertBundleWithoutComponents(shop, "H-BUNDLE");
    CatalogHttp.Result holds = http.get(OrderHttp.ordersPath("/holds"), httpShop.owner());
    assertThat(holds.status()).isEqualTo(200);
    JsonNode groups = holds.body().path("groups");
    assertThat(groups)
        .anySatisfy(
            g -> {
              assertThat(g.path("hold_reason").asString()).isEqualTo("SKU_NOT_MAPPED");
              assertThat(g.path("channel_account_counts").size()).isEqualTo(2);
              int mappedAccountTotal = 0;
              for (JsonNode row : g.path("channel_account_counts")) {
                mappedAccountTotal += row.path("count").asInt();
              }
              assertThat(mappedAccountTotal).isEqualTo(2);
            });
    assertThat(groups)
        .anySatisfy(
            g -> {
              assertThat(g.path("hold_reason").asString()).isEqualTo("OUT_OF_STOCK");
              assertThat(g.path("hold_detail").asString()).isBlank();
              assertThat(g.path("count").asInt()).isGreaterThanOrEqualTo(1);
            });
    assertThat(groups)
        .anySatisfy(
            g -> {
              assertThat(g.path("hold_reason").asString()).isEqualTo("OUT_OF_STOCK");
              assertThat(g.path("hold_detail").asString()).isEqualTo("BUNDLE_WITHOUT_COMPONENTS");
            });
    int total = 0;
    for (JsonNode group : groups) {
      total += group.path("count").asInt();
    }
    assertThat(total).isEqualTo(4);
  }

  private void insertBundleWithoutComponents(OrderFixture.Shop shop, String externalId) {
    UUID product = UuidV7.generate();
    UUID bundle = UuidV7.generate();
    fixture.inTenant(
        shop.tenantId(),
        () -> {
          jdbc.update(
              "INSERT INTO product (id, tenant_id, name, status) VALUES (?, ?, 'P', 'ACTIVE')",
              product,
              shop.tenantId());
          jdbc.update(
              "INSERT INTO sku (id, tenant_id, product_id, sku_code, name, is_bundle) VALUES (?, ?, ?, 'B-E', 'Bundle', true)",
              bundle,
              shop.tenantId(),
              product);
          SalesOrder order = fixture.insert(shop, externalId, "UNFULFILLED", "OUT_OF_STOCK");
          jdbc.update(
              """
              INSERT INTO order_line (
                id, tenant_id, order_id, external_line_id, external_sku_id, sku_id, name, qty, unit_price
              ) VALUES (?, ?, ?, 'L1', 'ext', ?, 'line', 1, 10)
              """,
              UuidV7.generate(),
              shop.tenantId(),
              order.id(),
              bundle);
          return null;
        });
  }
}
