package com.thaishopfun.oms.warehouse;

import static com.thaishopfun.oms.catalog.CatalogHttp.audits;
import static com.thaishopfun.oms.catalog.CatalogHttp.auditsFor;
import static com.thaishopfun.oms.catalog.CatalogHttp.count;
import static org.assertj.core.api.Assertions.assertThat;

import com.thaishopfun.oms.catalog.CatalogHttp;
import com.thaishopfun.oms.catalog.CatalogIntegrationTest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class WarehouseApiTest extends CatalogIntegrationTest {

  @Test
  void crudAndDefaultSwitch() {
    CatalogHttp.Shop shop = http.shop();

    // Step 1: The first list creates MAIN as the default, once.
    JsonNode first = http.get("/api/v1/warehouses", shop.owner()).body();
    assertThat(first.path("items").size()).isEqualTo(1);
    JsonNode main = first.path("items").get(0);
    assertThat(main.path("code").asString()).isEqualTo("MAIN");
    assertThat(main.path("is_default").asBoolean()).isTrue();
    assertThat(http.get("/api/v1/warehouses", shop.owner()).body().path("items").size())
        .isEqualTo(1);
    String mainId = main.path("id").asString();
    assertThat(auditsFor(shop.tenantId(), "WAREHOUSE_CREATED", mainId)).isEqualTo(1);

    // Step 2: Create, read, update. A duplicate code is 409.
    CatalogHttp.Result created =
        http.post(
            "/api/v1/warehouses",
            shop.owner(),
            Map.of("code", "BKK", "name", "Bangkok", "address", Map.of("province", "Bangkok")));
    assertThat(created.status()).as(created.raw()).isEqualTo(201);
    String bkk = created.body().path("id").asString();
    assertThat(created.body().path("is_default").asBoolean()).isFalse();
    assertThat(created.body().path("address").path("province").asString()).isEqualTo("Bangkok");
    CatalogHttp.Result duplicate =
        http.post("/api/v1/warehouses", shop.owner(), Map.of("code", "BKK", "name", "Again"));
    assertThat(duplicate.status()).isEqualTo(409);
    assertThat(duplicate.error()).isEqualTo("WAREHOUSE_CODE_EXISTS");
    CatalogHttp.Result updated =
        http.put(
            "/api/v1/warehouses/" + bkk, shop.owner(), Map.of("code", "BKK", "name", "Bangkok 2"));
    assertThat(updated.body().path("name").asString()).isEqualTo("Bangkok 2");
    assertThat(updated.body().path("address").isNull()).isTrue();
    assertThat(auditsFor(shop.tenantId(), "WAREHOUSE_UPDATED", bkk)).isEqualTo(1);
    assertThat(
            count(
                "SELECT count(*) FROM audit_log WHERE tenant_id = ? AND \"after\"::text LIKE '%Bangkok\"%'"
                    + " AND \"after\"::text LIKE '%province%'",
                shop.tenantId()))
        .isZero();

    // Step 3: Switch the default. Exactly one default remains.
    CatalogHttp.Result switched =
        http.post("/api/v1/warehouses/" + bkk + "/default", shop.owner(), null);
    assertThat(switched.status()).as(switched.raw()).isEqualTo(200);
    assertThat(switched.body().path("is_default").asBoolean()).isTrue();
    assertThat(defaults(shop)).isEqualTo(1);
    assertThat(auditsFor(shop.tenantId(), "WAREHOUSE_DEFAULT_CHANGED", bkk)).isEqualTo(1);

    // Step 4: The default cannot be deleted; the old one now can.
    CatalogHttp.Result deleteDefault = http.delete("/api/v1/warehouses/" + bkk, shop.owner());
    assertThat(deleteDefault.status()).isEqualTo(409);
    assertThat(deleteDefault.error()).isEqualTo("WAREHOUSE_IS_DEFAULT");
    assertThat(http.delete("/api/v1/warehouses/" + mainId, shop.owner()).status()).isEqualTo(204);
    assertThat(auditsFor(shop.tenantId(), "WAREHOUSE_DELETED", mainId)).isEqualTo(1);

    // Step 5: A warehouse with stock rows is in use.
    String spare =
        http.post("/api/v1/warehouses", shop.owner(), Map.of("code", "SPARE", "name", "Spare"))
            .body()
            .path("id")
            .asString();
    UUID sku = sku(shop, "W-1");
    CatalogHttp.seedInventory(shop.tenantId(), sku, UUID.fromString(spare));
    CatalogHttp.Result inUse = http.delete("/api/v1/warehouses/" + spare, shop.owner());
    assertThat(inUse.status()).isEqualTo(409);
    assertThat(inUse.error()).isEqualTo("WAREHOUSE_IN_USE");
  }

  @Test
  void defaultIsCreatedOnceUnderConcurrentFirstCalls() throws Exception {
    CatalogHttp.Shop shop = http.shop();
    List<CatalogHttp.Result> results =
        parallel(8, () -> http.get("/api/v1/warehouses", shop.owner()));
    for (CatalogHttp.Result result : results) {
      assertThat(result.status()).as(result.raw()).isEqualTo(200);
      assertThat(result.body().path("items").size()).isEqualTo(1);
    }
    assertThat(count("SELECT count(*) FROM warehouse WHERE tenant_id = ?", shop.tenantId()))
        .isEqualTo(1);
    assertThat(defaults(shop)).isEqualTo(1);
    assertThat(audits(shop.tenantId(), "WAREHOUSE_CREATED")).isEqualTo(1);
  }

  @Test
  void concurrentDefaultSwitchesKeepExactlyOne() throws Exception {
    CatalogHttp.Shop shop = http.shop();
    http.get("/api/v1/warehouses", shop.owner());
    List<String> ids = new ArrayList<>();
    for (String code : List.of("W1", "W2", "W3", "W4")) {
      ids.add(
          http.post("/api/v1/warehouses", shop.owner(), Map.of("code", code, "name", code))
              .body()
              .path("id")
              .asString());
    }
    for (int round = 0; round < 3; round++) {
      List<Integer> statuses = new ArrayList<>();
      int[] next = {0};
      List<CatalogHttp.Result> results =
          parallel(
              ids.size(),
              () -> {
                String id;
                synchronized (next) {
                  id = ids.get(next[0]++);
                }
                return http.post("/api/v1/warehouses/" + id + "/default", shop.owner(), null);
              });
      for (CatalogHttp.Result result : results) {
        statuses.add(result.status());
      }
      assertThat(statuses).as(results.toString()).allMatch(status -> status == 200);
      assertThat(defaults(shop)).isEqualTo(1);
    }
  }

  @Test
  void staffAndOtherTenants() {
    CatalogHttp.Shop a = http.shop();
    CatalogHttp.Shop b = http.shop();
    String main =
        http.get("/api/v1/warehouses", a.owner()).body().path("items").get(0).path("id").asString();

    // Step 1: STAFF can list but not change warehouses.
    String staff = http.member(a, "STAFF");
    assertThat(http.get("/api/v1/warehouses", staff).status()).isEqualTo(200);
    CatalogHttp.Result forbidden =
        http.post("/api/v1/warehouses", staff, Map.of("code", "X", "name", "X"));
    assertThat(forbidden.status()).isEqualTo(403);
    assertThat(forbidden.error()).isEqualTo("FORBIDDEN");
    assertThat(http.post("/api/v1/warehouses/" + main + "/default", staff, null).status())
        .isEqualTo(403);

    // Step 2: Another tenant gets 404 for A's warehouse, on read and on write.
    for (CatalogHttp.Result result :
        List.of(
            http.get("/api/v1/warehouses/" + main, b.owner()),
            http.put("/api/v1/warehouses/" + main, b.owner(), Map.of("code", "M", "name", "M")),
            http.post("/api/v1/warehouses/" + main + "/default", b.owner(), null),
            http.delete("/api/v1/warehouses/" + main, b.owner()))) {
      assertThat(result.status()).as(result.raw()).isEqualTo(404);
      assertThat(result.error()).isEqualTo("NOT_FOUND");
    }
  }

  private long defaults(CatalogHttp.Shop shop) {
    return count(
        "SELECT count(*) FROM warehouse WHERE tenant_id = ? AND is_default", shop.tenantId());
  }

  private UUID sku(CatalogHttp.Shop shop, String code) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("product_name", "P " + code);
    body.put("sku_code", code);
    body.put("name", code);
    return UUID.fromString(
        http.post("/api/v1/skus", shop.owner(), body).body().path("id").asString());
  }

  private static List<CatalogHttp.Result> parallel(
      int threads, java.util.concurrent.Callable<CatalogHttp.Result> call) throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    try {
      CyclicBarrier barrier = new CyclicBarrier(threads);
      List<Future<CatalogHttp.Result>> futures = new ArrayList<>();
      for (int i = 0; i < threads; i++) {
        futures.add(
            pool.submit(
                () -> {
                  barrier.await(10, TimeUnit.SECONDS);
                  return call.call();
                }));
      }
      List<CatalogHttp.Result> results = new ArrayList<>();
      for (Future<CatalogHttp.Result> future : futures) {
        results.add(future.get(60, TimeUnit.SECONDS));
      }
      return results;
    } finally {
      pool.shutdownNow();
    }
  }
}
