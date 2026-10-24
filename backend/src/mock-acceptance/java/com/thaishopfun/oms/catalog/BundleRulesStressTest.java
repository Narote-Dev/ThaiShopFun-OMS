package com.thaishopfun.oms.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.thaishopfun.oms.invariant.VerifyInvariants;
import com.thaishopfun.oms.stock.StockFixture;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * API validation and the V4 trigger independently reject nesting, self-cycles and reverse edges.
 */
@VerifyInvariants
class BundleRulesStressTest extends CatalogIntegrationTest {
  @Autowired JdbcTemplate jdbc;
  @Autowired PlatformTransactionManager transactions;

  @Test
  void nestedAndCircularBundlesAreRejectedByApiAndDatabase() {
    CatalogHttp.Shop shop = http.shop();
    CatalogHttp.Result product = http.post("/api/v1/products", shop.owner(), Map.of("name", "T09"));
    assertThat(product.status()).as(product.raw()).isEqualTo(201);
    String productId = product.body().path("id").asString();
    UUID component = sku(shop, productId, "COMPONENT", false);
    UUID first = sku(shop, productId, "FIRST", true);
    UUID second = sku(shop, productId, "SECOND", true);
    CatalogHttp.Result initial =
        http.put(
            "/api/v1/skus/" + first + "/components",
            shop.owner(),
            List.of(Map.of("component_sku_id", component, "qty", 2)));
    assertThat(initial.status()).as(initial.raw()).isEqualTo(200);
    StockFixture fixture = new StockFixture(jdbc, transactions);

    // Both directions are blocked, so no two-bundle cycle can be formed; self cycles are blocked
    // too.
    for (List<UUID> edge :
        List.of(List.of(second, first), List.of(first, second), List.of(first, first))) {
      CatalogHttp.Result api =
          http.put(
              "/api/v1/skus/" + edge.get(0) + "/components",
              shop.owner(),
              List.of(Map.of("component_sku_id", edge.get(1), "qty", 1)));
      assertThat(api.status()).as(api.raw()).isEqualTo(422);
      assertThat(api.error()).isEqualTo("NESTED_BUNDLE");
      Throwable db =
          catchThrowable(
              () ->
                  fixture.runInTenant(
                      shop.tenantId(),
                      () ->
                          jdbc.update(
                              "INSERT INTO sku_bundle_component (tenant_id, bundle_sku_id, component_sku_id, qty) "
                                  + "VALUES (?, ?, ?, 1)",
                              shop.tenantId(),
                              edge.get(0),
                              edge.get(1))));
      assertThat(db).isNotNull();
      assertThat(SqlErrors.failure(db))
          .isEqualTo(new SqlErrors.Failure("23514", "sku_bundle_nesting"));
    }

    // Conversion of a component would introduce nesting through an existing valid edge.
    CatalogHttp.Result conversion =
        http.put(
            "/api/v1/skus/" + component,
            shop.owner(),
            Map.of(
                "product_id",
                productId,
                "sku_code",
                "COMPONENT",
                "name",
                "Component",
                "is_bundle",
                true));
    assertThat(conversion.status()).as(conversion.raw()).isEqualTo(422);
    assertThat(conversion.error()).isEqualTo("NESTED_BUNDLE");
    Throwable conversionDb =
        catchThrowable(
            () ->
                fixture.runInTenant(
                    shop.tenantId(),
                    () -> jdbc.update("UPDATE sku SET is_bundle = true WHERE id = ?", component)));
    assertThat(conversionDb).isNotNull();
    assertThat(SqlErrors.failure(conversionDb))
        .isEqualTo(new SqlErrors.Failure("23514", "sku_bundle_nesting"));

    // Rejected replacement must preserve every original edge, including its quantity.
    fixture.runInTenant(
        shop.tenantId(),
        () -> {
          assertThat(jdbc.queryForObject("SELECT current_user", String.class)).isEqualTo("oms_app");
          var edges =
              jdbc.queryForList(
                  "SELECT bundle_sku_id, component_sku_id, qty FROM sku_bundle_component");
          assertThat(edges)
              .containsExactly(
                  Map.of("bundle_sku_id", first, "component_sku_id", component, "qty", 2));
          assertThat(
                  jdbc.queryForObject(
                      "SELECT is_bundle FROM sku WHERE id = ?", Boolean.class, component))
              .isFalse();
        });
  }

  private UUID sku(CatalogHttp.Shop shop, String product, String code, boolean bundle) {
    CatalogHttp.Result result =
        http.post(
            "/api/v1/skus",
            shop.owner(),
            Map.of("product_id", product, "sku_code", code, "name", code, "is_bundle", bundle));
    assertThat(result.status()).as(result.raw()).isEqualTo(201);
    return UUID.fromString(result.body().path("id").asString());
  }
}
