package com.thaishopfun.oms.order.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.thaishopfun.oms.auth.AuthTestSupport;
import com.thaishopfun.oms.catalog.CatalogHttp;
import java.sql.Connection;
import java.sql.PreparedStatement;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** Demo catalog seed for mock-tsf demo orders ({@code local}/{@code e2e} profiles). */
class OrderDemoCatalogApiTest extends OrderIntegrationTest {

  @Autowired JdbcTemplate jdbc;

  @Test
  void ensureDemoCatalogCreatesListingsForShopActive() throws Exception {
    CatalogHttp.Shop httpShop = http.catalog().shop();
    try (Connection admin = AuthTestSupport.admin();
        PreparedStatement ps =
            admin.prepareStatement("UPDATE tenant SET tsf_shop_id = ? WHERE id = ?")) {
      ps.setString(1, "shop_active");
      ps.setObject(2, httpShop.tenantId());
      ps.executeUpdate();
    }
    OrderFixture.shopFor(httpShop);
    CatalogHttp.Result seed =
        http.post("/control/demo/order-catalog", httpShop.owner(), java.util.Map.of());
    assertThat(seed.status()).isEqualTo(200);
    assertThat(seed.body().path("status").asString()).isEqualTo("OK");
    Long listings =
        jdbc.queryForObject(
            "SELECT count(*) FROM channel_listing WHERE tenant_id = ? AND external_sku_id LIKE 'L-demo-%'",
            Long.class,
            httpShop.tenantId());
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
            httpShop.tenantId());
    assertThat(oosStock).isZero();
  }
}
