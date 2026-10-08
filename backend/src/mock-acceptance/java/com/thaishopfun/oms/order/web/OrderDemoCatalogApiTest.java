package com.thaishopfun.oms.order.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.thaishopfun.oms.auth.AuthTestSupport;
import com.thaishopfun.oms.catalog.CatalogHttp;
import com.thaishopfun.oms.invariant.InvariantChecker;
import com.thaishopfun.oms.invariant.InvariantCodes;
import com.thaishopfun.oms.invariant.VerifyInvariants;
import com.thaishopfun.oms.order.demo.OrderDemoCatalogService;
import com.thaishopfun.oms.tenant.TenantContext;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.json.JsonMapper;

/** Demo catalog seed for mock-tsf demo orders ({@code local}/{@code e2e} profiles). */
@VerifyInvariants
class OrderDemoCatalogApiTest extends OrderIntegrationTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final HttpClient HTTP =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

  @Autowired OrderDemoCatalogService catalog;
  @Autowired InvariantChecker invariantChecker;

  @AfterEach
  void clearTenant() throws Exception {
    TenantContext.clear();
    try (Connection admin = AuthTestSupport.admin();
        var ps =
            admin.prepareStatement(
                "UPDATE tenant SET name = 'Active Shop' WHERE tsf_shop_id = ?")) {
      ps.setString(1, "shop_active");
      ps.executeUpdate();
    }
  }

  @Test
  void httpPostOrderCatalogWithoutTenantContextSeedsShopActive() throws Exception {
    String owner =
        CatalogHttp.token("owner-" + UUID.randomUUID(), "shop_active", "OWNER", "ACTIVE");
    assertThat(http.get("/api/v1/me", owner).status()).isEqualTo(200);
    TenantContext.clear();
    HttpResponse<String> response =
        HTTP.send(
            HttpRequest.newBuilder(
                    URI.create("http://127.0.0.1:" + port + "/control/demo/order-catalog"))
                .POST(HttpRequest.BodyPublishers.noBody())
                .timeout(Duration.ofSeconds(20))
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(JSON.readTree(response.body()).path("status").asString()).isEqualTo("OK");
    UUID tenantId = UUID.fromString(JSON.readTree(response.body()).path("tenant_id").asString());
    try (Connection admin = AuthTestSupport.admin();
        PreparedStatement listingsPs =
            admin.prepareStatement(
                "SELECT count(*) FROM channel_listing WHERE tenant_id = ? AND external_sku_id LIKE 'L-demo-%'")) {
      listingsPs.setObject(1, tenantId);
      try (ResultSet rs = listingsPs.executeQuery()) {
        rs.next();
        assertThat(rs.getLong(1)).isGreaterThanOrEqualTo(5);
      }
    }
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
    assertThat(invariantChecker.checkTenant(shop.tenantId())).isEmpty();
  }

  @Test
  void ensureDemoCatalogReconcilesLedgerWhenOnHandDriftsFromLedger() throws Exception {
    CatalogHttp.Shop httpShop = http.catalog().shop();
    OrderFixture.Shop shop = OrderFixture.shopFor(httpShop);
    TenantContext.set(shop.tenantId(), null);
    catalog.ensureDemoCatalog();

    try (Connection admin = AuthTestSupport.admin();
        PreparedStatement drift =
            admin.prepareStatement(
                """
                UPDATE inventory i
                SET on_hand = on_hand + 7, stock_version = stock_version + 1
                FROM sku s
                WHERE s.tenant_id = i.tenant_id AND s.id = i.sku_id
                  AND s.tenant_id = ? AND s.sku_code = 'DEMO-SKU-READY'
                """)) {
      drift.setObject(1, shop.tenantId());
      assertThat(drift.executeUpdate()).isEqualTo(1);
    }

    assertThat(invariantChecker.checkTenant(shop.tenantId()))
        .anyMatch(v -> InvariantCodes.STOCK_LEDGER_MISMATCH.equals(v.code()));

    catalog.ensureDemoCatalog();
    assertThat(invariantChecker.checkTenant(shop.tenantId())).isEmpty();
  }
}
