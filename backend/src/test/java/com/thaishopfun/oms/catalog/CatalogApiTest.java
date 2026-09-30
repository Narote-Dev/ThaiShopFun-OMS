package com.thaishopfun.oms.catalog;

import static com.thaishopfun.oms.catalog.CatalogHttp.audits;
import static com.thaishopfun.oms.catalog.CatalogHttp.auditsFor;
import static com.thaishopfun.oms.catalog.CatalogHttp.catalogAudits;
import static com.thaishopfun.oms.catalog.CatalogHttp.count;
import static org.assertj.core.api.Assertions.assertThat;

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

/** T07 products, SKUs and bundles over HTTP, running as {@code oms_app} under FORCE RLS. */
class CatalogApiTest extends CatalogIntegrationTest {

  @Test
  void productCrudAndArchive() {
    CatalogHttp.Shop shop = http.shop();

    // Step 1: Create, read, list.
    CatalogHttp.Result created = http.post("/api/v1/products", shop.owner(), Map.of("name", "Tee"));
    assertThat(created.status()).as(created.raw()).isEqualTo(201);
    String id = created.body().path("id").asString();
    assertThat(created.body().path("status").asString()).isEqualTo("ACTIVE");
    assertThat(http.get("/api/v1/products/" + id, shop.owner()).body().path("name").asString())
        .isEqualTo("Tee");
    JsonNode list = http.get("/api/v1/products?q=te", shop.owner()).body();
    assertThat(list.path("total").asLong()).isEqualTo(1);

    // Step 2: Update and archive. Each change is one audit row for this product.
    CatalogHttp.Result updated =
        http.put("/api/v1/products/" + id, shop.owner(), Map.of("name", "T-Shirt"));
    assertThat(updated.status()).isEqualTo(200);
    assertThat(updated.body().path("name").asString()).isEqualTo("T-Shirt");
    CatalogHttp.Result archived =
        http.post("/api/v1/products/" + id + "/archive", shop.owner(), null);
    assertThat(archived.body().path("status").asString()).isEqualTo("INACTIVE");
    assertThat(auditsFor(shop.tenantId(), "PRODUCT_CREATED", id)).isEqualTo(1);
    assertThat(auditsFor(shop.tenantId(), "PRODUCT_UPDATED", id)).isEqualTo(1);
    assertThat(auditsFor(shop.tenantId(), "PRODUCT_ARCHIVED", id)).isEqualTo(1);

    // Step 3: A product with SKUs cannot be deleted. Without SKUs it can.
    createSku(shop, UUID.fromString(id), "TEE-1", false);
    CatalogHttp.Result inUse = http.delete("/api/v1/products/" + id, shop.owner());
    assertThat(inUse.status()).isEqualTo(409);
    assertThat(inUse.error()).isEqualTo("PRODUCT_IN_USE");
    String empty =
        http.post("/api/v1/products", shop.owner(), Map.of("name", "Empty"))
            .body()
            .path("id")
            .asString();
    assertThat(http.delete("/api/v1/products/" + empty, shop.owner()).status()).isEqualTo(204);
    assertThat(auditsFor(shop.tenantId(), "PRODUCT_DELETED", empty)).isEqualTo(1);

    // Step 4: Validation is a 422 in the section 4.8 shape.
    CatalogHttp.Result invalid = http.post("/api/v1/products", shop.owner(), Map.of("name", " "));
    assertThat(invalid.status()).isEqualTo(422);
    assertThat(invalid.error()).isEqualTo("VALIDATION_FAILED");
    assertThat(http.post("/api/v1/products", shop.owner(), "{not json").status()).isEqualTo(422);
  }

  @Test
  void skuCrudSearchAndPaging() {
    CatalogHttp.Shop shop = http.shop();

    // Step 1: Create with a new product in the same call, then read it back.
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("product_name", "Mug");
    body.put("sku_code", "MUG-RED");
    body.put("name", "Red coffee mug");
    body.put("barcode", "8850001112223");
    body.put("weight_g", 350);
    CatalogHttp.Result created = http.post("/api/v1/skus", shop.owner(), body);
    assertThat(created.status()).as(created.raw()).isEqualTo(201);
    JsonNode sku = created.body();
    String id = sku.path("id").asString();
    UUID productId = UUID.fromString(sku.path("product_id").asString());
    assertThat(sku.path("product_name").asString()).isEqualTo("Mug");
    assertThat(sku.path("is_bundle").asBoolean()).isFalse();
    assertThat(sku.path("on_hand").asInt()).isZero();
    assertThat(auditsFor(shop.tenantId(), "SKU_CREATED", id)).isEqualTo(1);
    assertThat(audits(shop.tenantId(), "PRODUCT_CREATED")).isEqualTo(1);
    createSku(shop, productId, "MUG-BLUE", false);
    createSku(shop, productId, "PLATE-1", false);

    // Step 2: Search by code prefix, exact barcode and name substring, ignoring case.
    assertThat(codes(http.get("/api/v1/skus?q=mug-", shop.owner())))
        .containsExactly("MUG-BLUE", "MUG-RED");
    assertThat(codes(http.get("/api/v1/skus?q=8850001112223", shop.owner())))
        .containsExactly("MUG-RED");
    assertThat(codes(http.get("/api/v1/skus?q=885000111", shop.owner()))).isEmpty();
    assertThat(codes(http.get("/api/v1/skus?q=COFFEE", shop.owner()))).containsExactly("MUG-RED");
    assertThat(codes(http.get("/api/v1/skus?q=%25", shop.owner()))).isEmpty();

    // Step 3: Paging is stable and complete.
    List<String> paged = new ArrayList<>();
    for (int offset = 0; offset < 3; offset++) {
      JsonNode page = http.get("/api/v1/skus?limit=1&offset=" + offset, shop.owner()).body();
      assertThat(page.path("total").asLong()).isEqualTo(3);
      paged.addAll(codes(page));
    }
    assertThat(paged).containsExactly("MUG-BLUE", "MUG-RED", "PLATE-1");

    // Step 4: Update, then delete an unreferenced SKU.
    Map<String, Object> update = new LinkedHashMap<>();
    update.put("product_id", productId.toString());
    update.put("sku_code", "MUG-RED-L");
    update.put("name", "Red coffee mug, large");
    update.put("weight_g", 420);
    CatalogHttp.Result updated = http.put("/api/v1/skus/" + id, shop.owner(), update);
    assertThat(updated.status()).as(updated.raw()).isEqualTo(200);
    assertThat(updated.body().path("sku_code").asString()).isEqualTo("MUG-RED-L");
    assertThat(updated.body().path("barcode").isNull()).isTrue();
    assertThat(auditsFor(shop.tenantId(), "SKU_UPDATED", id)).isEqualTo(1);
    assertThat(http.delete("/api/v1/skus/" + id, shop.owner()).status()).isEqualTo(204);
    assertThat(http.get("/api/v1/skus/" + id, shop.owner()).status()).isEqualTo(404);
    assertThat(auditsFor(shop.tenantId(), "SKU_DELETED", id)).isEqualTo(1);
    assertThat(http.get("/api/v1/skus/not-a-uuid", shop.owner()).status()).isEqualTo(404);
  }

  @Test
  void duplicateSkuCodeIs409AndConcurrentCreateHasOneWinner() throws Exception {
    CatalogHttp.Shop shop = http.shop();
    UUID product = createProduct(shop, "Socks");
    createSku(shop, product, "SOCK-1", false);
    long auditsBefore = catalogAudits(shop.tenantId());

    // Step 1: Sequential duplicate. The failed insert leaves no audit row.
    CatalogHttp.Result duplicate =
        http.post("/api/v1/skus", shop.owner(), skuBody(product, "SOCK-1", false));
    assertThat(duplicate.status()).isEqualTo(409);
    assertThat(duplicate.error()).isEqualTo("SKU_CODE_EXISTS");
    assertThat(catalogAudits(shop.tenantId())).isEqualTo(auditsBefore);

    // Step 2: Two concurrent creates of a new code: exactly one 201 and one 409.
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      CyclicBarrier barrier = new CyclicBarrier(2);
      List<Future<CatalogHttp.Result>> calls = new ArrayList<>();
      for (int i = 0; i < 2; i++) {
        calls.add(
            pool.submit(
                () -> {
                  barrier.await(10, TimeUnit.SECONDS);
                  return http.post("/api/v1/skus", shop.owner(), skuBody(product, "SOCK-2", false));
                }));
      }
      List<Integer> statuses = new ArrayList<>();
      for (Future<CatalogHttp.Result> call : calls) {
        CatalogHttp.Result result = call.get(30, TimeUnit.SECONDS);
        statuses.add(result.status());
        if (result.status() == 409) {
          assertThat(result.error()).isEqualTo("SKU_CODE_EXISTS");
        }
      }
      assertThat(statuses).containsExactlyInAnyOrder(201, 409);
    } finally {
      pool.shutdownNow();
    }
    assertThat(
            count(
                "SELECT count(*) FROM sku WHERE tenant_id = ? AND sku_code = 'SOCK-2'",
                shop.tenantId()))
        .isEqualTo(1);
    assertThat(audits(shop.tenantId(), "SKU_CREATED")).isEqualTo(2);
  }

  @Test
  void bundleRules() {
    CatalogHttp.Shop shop = http.shop();
    UUID product = createProduct(shop, "Gift set");
    String a = createSku(shop, product, "A", false);
    String b = createSku(shop, product, "B", false);
    String bundle = createSku(shop, product, "SET-1", true);
    String other = createSku(shop, product, "SET-2", true);

    // Step 1: Replace components by id and by code, in one call.
    CatalogHttp.Result replaced =
        http.put(
            "/api/v1/skus/" + bundle + "/components",
            shop.owner(),
            List.of(
                Map.of("component_sku_id", a, "qty", 2),
                Map.of("component_sku_code", "B", "qty", 1)));
    assertThat(replaced.status()).as(replaced.raw()).isEqualTo(200);
    assertThat(replaced.body().path("components").size()).isEqualTo(2);
    assertThat(replaced.body().path("on_hand").isNull()).isTrue();
    assertThat(auditsFor(shop.tenantId(), "BUNDLE_COMPONENTS_REPLACED", bundle)).isEqualTo(1);
    String stocked = createSku(shop, product, "STOCKED", false);
    UUID warehouse = defaultWarehouse(shop);
    CatalogHttp.seedInventory(shop.tenantId(), UUID.fromString(stocked), warehouse);
    long auditsBeforeRejected = catalogAudits(shop.tenantId());

    // Step 2: A bundle as a component, or a bundle in itself, is NESTED_BUNDLE.
    expectError(
        http.put(
            "/api/v1/skus/" + other + "/components",
            shop.owner(),
            List.of(Map.of("component_sku_id", bundle, "qty", 1))),
        422,
        "NESTED_BUNDLE");
    expectError(
        http.put(
            "/api/v1/skus/" + bundle + "/components",
            shop.owner(),
            List.of(Map.of("component_sku_id", bundle, "qty", 1))),
        422,
        "NESTED_BUNDLE");
    // A component cannot become a bundle either.
    expectError(
        http.put("/api/v1/skus/" + a, shop.owner(), skuBody(product, "A", true)),
        422,
        "NESTED_BUNDLE");

    // Step 3: Components on a non-bundle, or clearing is_bundle with components: BUNDLE_REQUIRED.
    expectError(
        http.put(
            "/api/v1/skus/" + b + "/components",
            shop.owner(),
            List.of(Map.of("component_sku_id", a, "qty", 1))),
        422,
        "BUNDLE_REQUIRED");
    expectError(
        http.put("/api/v1/skus/" + bundle, shop.owner(), skuBody(product, "SET-1", false)),
        422,
        "BUNDLE_REQUIRED");

    // Step 4: A SKU with an inventory row (seeded above via JDBC) cannot become a bundle.
    expectError(
        http.put("/api/v1/skus/" + stocked, shop.owner(), skuBody(product, "STOCKED", true)),
        422,
        "BUNDLE_NOT_STOCKABLE");
    JsonNode stockedView = http.get("/api/v1/skus/" + stocked, shop.owner()).body();
    assertThat(stockedView.path("on_hand").asInt()).isEqualTo(7);
    assertThat(stockedView.path("reserved").asInt()).isEqualTo(2);

    // Step 5: Referenced SKUs cannot be deleted (component, stock row).
    expectError(http.delete("/api/v1/skus/" + a, shop.owner()), 409, "SKU_IN_USE");
    expectError(http.delete("/api/v1/skus/" + stocked, shop.owner()), 409, "SKU_IN_USE");

    // Step 6: None of the rejected calls wrote an audit row.
    assertThat(catalogAudits(shop.tenantId())).isEqualTo(auditsBeforeRejected);

    // Step 7: Clear the list, then is_bundle can be cleared, and a bundle can be deleted.
    CatalogHttp.Result cleared =
        http.put("/api/v1/skus/" + bundle + "/components", shop.owner(), List.of());
    assertThat(cleared.body().path("components").size()).isZero();
    assertThat(
            http.put("/api/v1/skus/" + bundle, shop.owner(), skuBody(product, "SET-1", false))
                .status())
        .isEqualTo(200);
    http.put(
        "/api/v1/skus/" + other + "/components",
        shop.owner(),
        List.of(Map.of("component_sku_id", a, "qty", 3)));
    assertThat(http.delete("/api/v1/skus/" + other, shop.owner()).status()).isEqualTo(204);
    assertThat(
            count(
                "SELECT count(*) FROM sku_bundle_component WHERE bundle_sku_id = ?",
                UUID.fromString(other)))
        .isZero();
  }

  @Test
  void staffCannotWriteAndGraceIsReadOnly() {
    CatalogHttp.Shop shop = http.shop();
    UUID product = createProduct(shop, "Hat");
    String sku = createSku(shop, product, "HAT-1", false);
    long before = catalogAudits(shop.tenantId());

    // Step 1: STAFF reads, but every write is 403 FORBIDDEN.
    String staff = http.member(shop, "STAFF");
    assertThat(http.get("/api/v1/skus", staff).status()).isEqualTo(200);
    assertThat(http.get("/api/v1/skus/" + sku, staff).status()).isEqualTo(200);
    expectError(
        http.post("/api/v1/skus", staff, skuBody(product, "HAT-2", false)), 403, "FORBIDDEN");
    expectError(
        http.put("/api/v1/skus/" + sku, staff, skuBody(product, "HAT-1", false)), 403, "FORBIDDEN");
    expectError(http.delete("/api/v1/skus/" + sku, staff), 403, "FORBIDDEN");
    expectError(http.post("/api/v1/products", staff, Map.of("name", "X")), 403, "FORBIDDEN");
    expectError(
        http.put("/api/v1/skus/" + sku + "/components", staff, List.of()), 403, "FORBIDDEN");
    expectError(
        http.upload(
            "/api/v1/catalog/import", staff, "c.csv", "product_name,sku_code,sku_name\nP,X,Y\n"),
        403,
        "FORBIDDEN");

    // Step 2: ADMIN can write.
    String admin = http.member(shop, "ADMIN");
    assertThat(http.post("/api/v1/skus", admin, skuBody(product, "HAT-3", false)).status())
        .isEqualTo(201);

    // Step 3: GRACE is read-only through the existing gate. The shop's first login is GRACE.
    String graceShop = "shop-" + UUID.randomUUID();
    String graceOwner =
        CatalogHttp.token("grace-" + UUID.randomUUID(), graceShop, "OWNER", "GRACE");
    assertThat(http.get("/api/v1/skus", graceOwner).status()).isEqualTo(200);
    expectError(
        http.post("/api/v1/products", graceOwner, Map.of("name", "X")), 403, "ENTITLEMENT_GRACE");
    expectError(
        http.upload(
            "/api/v1/catalog/import",
            graceOwner,
            "c.csv",
            "product_name,sku_code,sku_name\nP,X,Y\n"),
        403,
        "ENTITLEMENT_GRACE");
    assertThat(catalogAudits(shop.tenantId())).isEqualTo(before + 1);
  }

  @Test
  void otherTenantsSkuIsNotFound() {
    CatalogHttp.Shop a = http.shop();
    CatalogHttp.Shop b = http.shop();
    UUID product = createProduct(a, "Private");
    String sku = createSku(a, product, "PRIV-1", false);

    // Step 1: B cannot see, change, or delete A's rows by id: 404, never 403.
    expectError(http.get("/api/v1/skus/" + sku, b.owner()), 404, "NOT_FOUND");
    expectError(
        http.put("/api/v1/skus/" + sku, b.owner(), skuBody(product, "PRIV-1", false)),
        404,
        "NOT_FOUND");
    expectError(http.delete("/api/v1/skus/" + sku, b.owner()), 404, "NOT_FOUND");
    expectError(http.get("/api/v1/products/" + product, b.owner()), 404, "NOT_FOUND");
    expectError(
        http.put("/api/v1/skus/" + sku + "/components", b.owner(), List.of()), 404, "NOT_FOUND");
    assertThat(http.get("/api/v1/skus?q=PRIV", b.owner()).body().path("total").asLong()).isZero();

    // Step 2: B cannot attach A's product or A's SKU as a component either.
    expectError(
        http.post("/api/v1/skus", b.owner(), skuBody(product, "B-1", false)),
        422,
        "VALIDATION_FAILED");
    assertThat(http.get("/api/v1/skus/" + sku, a.owner()).status()).isEqualTo(200);
  }

  private UUID createProduct(CatalogHttp.Shop shop, String name) {
    CatalogHttp.Result result = http.post("/api/v1/products", shop.owner(), Map.of("name", name));
    assertThat(result.status()).as(result.raw()).isEqualTo(201);
    return UUID.fromString(result.body().path("id").asString());
  }

  private String createSku(CatalogHttp.Shop shop, UUID product, String code, boolean bundle) {
    CatalogHttp.Result result =
        http.post("/api/v1/skus", shop.owner(), skuBody(product, code, bundle));
    assertThat(result.status()).as(result.raw()).isEqualTo(201);
    return result.body().path("id").asString();
  }

  private UUID defaultWarehouse(CatalogHttp.Shop shop) {
    JsonNode list = http.get("/api/v1/warehouses", shop.owner()).body();
    for (JsonNode item : list.path("items")) {
      if (item.path("is_default").asBoolean()) {
        return UUID.fromString(item.path("id").asString());
      }
    }
    throw new AssertionError("no default warehouse");
  }

  static Map<String, Object> skuBody(UUID product, String code, boolean bundle) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("product_id", product.toString());
    body.put("sku_code", code);
    body.put("name", "SKU " + code);
    body.put("is_bundle", bundle);
    return body;
  }

  static void expectError(CatalogHttp.Result result, int status, String error) {
    assertThat(result.status()).as(result.raw()).isEqualTo(status);
    assertThat(result.error()).as(result.raw()).isEqualTo(error);
  }

  private static List<String> codes(CatalogHttp.Result result) {
    return codes(result.body());
  }

  private static List<String> codes(JsonNode page) {
    List<String> codes = new ArrayList<>();
    for (JsonNode item : page.path("items")) {
      codes.add(item.path("sku_code").asString());
    }
    return codes;
  }
}
