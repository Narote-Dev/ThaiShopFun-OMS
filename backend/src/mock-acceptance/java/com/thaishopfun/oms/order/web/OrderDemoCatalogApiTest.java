package com.thaishopfun.oms.order.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.thaishopfun.oms.catalog.CatalogHttp;
import com.thaishopfun.oms.order.demo.OrderDemoCatalogService;
import com.thaishopfun.oms.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** Demo catalog seed for mock-tsf demo orders ({@code local}/{@code e2e} profiles). */
class OrderDemoCatalogApiTest extends OrderIntegrationTest {

  @Autowired JdbcTemplate jdbc;
  @Autowired OrderDemoCatalogService catalog;

  @AfterEach
  void clearTenant() {
    TenantContext.clear();
  }

  @Test
  void ensureDemoCatalogCreatesListingsForShopActive() throws Exception {
    CatalogHttp.Shop httpShop = http.catalog().shop();
    OrderFixture.Shop shop = OrderFixture.shopFor(httpShop);
    TenantContext.set(shop.tenantId(), null);
    var result = catalog.ensureDemoCatalog();
    assertThat(result.get("status")).isEqualTo("OK");
    Long listings =
        jdbc.queryForObject(
            "SELECT count(*) FROM channel_listing WHERE tenant_id = ? AND external_sku_id LIKE 'L-demo-%'",
            Long.class,
            shop.tenantId());
    assertThat(listings).isGreaterThanOrEqualTo(5);
    Long oosStock =
        jdbc.queryForObject(
            """
            SELECT i.on_hand FROM inventory i
            JOIN sku s ON s.id = i.sku_id AND s.tenant_id = i.tenant_id
            WHERE s.tenant_id = ? AND s.sku_code = 'DEMO-SKU-OOS'
            LIMIT 1
            """,
            Long.class,
            shop.tenantId());
    assertThat(oosStock).isZero();
  }
}
