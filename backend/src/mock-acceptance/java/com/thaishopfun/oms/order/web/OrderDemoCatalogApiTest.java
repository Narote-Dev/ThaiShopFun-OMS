package com.thaishopfun.oms.order.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.thaishopfun.oms.auth.AuthTestSupport;
import com.thaishopfun.oms.catalog.CatalogHttp;
import com.thaishopfun.oms.order.demo.OrderDemoCatalogService;
import com.thaishopfun.oms.tenant.TenantContext;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Demo catalog seed for mock-tsf demo orders ({@code local}/{@code e2e} profiles). */
class OrderDemoCatalogApiTest extends OrderIntegrationTest {

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
    try (Connection admin = AuthTestSupport.admin();
        PreparedStatement listingsPs =
            admin.prepareStatement(
                "SELECT count(*) FROM channel_listing WHERE tenant_id = ? AND external_sku_id LIKE 'L-demo-%'")) {
      listingsPs.setObject(1, shop.tenantId());
      try (ResultSet rs = listingsPs.executeQuery()) {
        rs.next();
        assertThat(rs.getLong(1)).isGreaterThanOrEqualTo(5);
      }
    }
    try (Connection admin = AuthTestSupport.admin();
        PreparedStatement stockPs =
            admin.prepareStatement(
                """
                SELECT i.on_hand FROM inventory i
                JOIN sku s ON s.id = i.sku_id AND s.tenant_id = i.tenant_id
                WHERE s.tenant_id = ? AND s.sku_code = 'DEMO-SKU-OOS'
                LIMIT 1
                """)) {
      stockPs.setObject(1, shop.tenantId());
      try (ResultSet rs = stockPs.executeQuery()) {
        assertThat(rs.next()).isTrue();
        assertThat(rs.getLong(1)).isZero();
      }
    }
  }
}
