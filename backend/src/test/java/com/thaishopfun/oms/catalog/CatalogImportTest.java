package com.thaishopfun.oms.catalog;

import static com.thaishopfun.oms.catalog.CatalogHttp.audits;
import static com.thaishopfun.oms.catalog.CatalogHttp.catalogAudits;
import static com.thaishopfun.oms.catalog.CatalogHttp.count;
import static com.thaishopfun.oms.catalog.CatalogHttp.text;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

/** T07 CSV import: 1,000 rows under 10 s, all-or-nothing, exact bad row numbers, idempotent. */
class CatalogImportTest extends CatalogIntegrationTest {

  private static final Logger log = LoggerFactory.getLogger(CatalogImportTest.class);
  private static final String HEADER =
      "product_name,sku_code,sku_name,barcode,weight_g,is_bundle,components\n";

  @Test
  void thousandRowsWithBundlesImportUnderTenSecondsAndReimportIsIdempotent() {
    CatalogHttp.Shop shop = http.shop();
    String csv = thousandRows();

    // Step 1: 1,000 rows (900 SKUs, 100 bundles that reference SKUs later in the file).
    long started = System.nanoTime();
    CatalogHttp.Result first =
        http.upload("/api/v1/catalog/import", shop.owner(), "catalog.csv", csv);
    long elapsedMs = (System.nanoTime() - started) / 1_000_000;
    log.info(
        "T07 import of 1000 rows took {} ms (server {} ms)",
        elapsedMs,
        first.body().path("elapsed_ms"));
    assertThat(first.status()).as(first.raw()).isEqualTo(200);
    assertThat(elapsedMs).isLessThan(10_000);
    JsonNode body = first.body();
    assertThat(body.path("rows").asInt()).isEqualTo(1000);
    assertThat(body.path("skus_created").asInt()).isEqualTo(1000);
    assertThat(body.path("products_created").asInt()).isEqualTo(50);
    assertThat(body.path("bundles_replaced").asInt()).isEqualTo(100);
    assertThat(count("SELECT count(*) FROM sku WHERE tenant_id = ?", shop.tenantId()))
        .isEqualTo(1000);
    assertThat(count("SELECT count(*) FROM sku WHERE tenant_id = ? AND is_bundle", shop.tenantId()))
        .isEqualTo(100);
    assertThat(
            count("SELECT count(*) FROM sku_bundle_component WHERE tenant_id = ?", shop.tenantId()))
        .isEqualTo(200);

    // Step 2: One summary row plus per-entity rows. The default warehouse exists.
    assertThat(audits(shop.tenantId(), "CATALOG_IMPORTED")).isEqualTo(1);
    assertThat(audits(shop.tenantId(), "SKU_CREATED")).isEqualTo(1000);
    assertThat(audits(shop.tenantId(), "PRODUCT_CREATED")).isEqualTo(50);
    assertThat(audits(shop.tenantId(), "BUNDLE_COMPONENTS_REPLACED")).isEqualTo(100);
    assertThat(
            count(
                "SELECT count(*) FROM warehouse WHERE tenant_id = ? AND is_default AND code = 'MAIN'",
                shop.tenantId()))
        .isEqualTo(1);

    // Step 3: Re-import of the same file changes nothing except one more summary row.
    long auditsBefore = catalogAudits(shop.tenantId());
    String snapshot = catalogSnapshot(shop.tenantId());
    CatalogHttp.Result again =
        http.upload("/api/v1/catalog/import", shop.owner(), "catalog.csv", csv);
    assertThat(again.status()).as(again.raw()).isEqualTo(200);
    assertThat(again.body().path("skus_unchanged").asInt()).isEqualTo(1000);
    assertThat(again.body().path("skus_created").asInt()).isZero();
    assertThat(again.body().path("skus_updated").asInt()).isZero();
    assertThat(again.body().path("bundles_replaced").asInt()).isZero();
    assertThat(catalogAudits(shop.tenantId())).isEqualTo(auditsBefore + 1);
    assertThat(catalogSnapshot(shop.tenantId())).isEqualTo(snapshot);
  }

  @Test
  void absentOptionalColumnsKeepStoredValuesAndEmptyCellsClearThem() {
    CatalogHttp.Shop shop = http.shop();
    String full = thousandRows();
    assertThat(http.upload("/api/v1/catalog/import", shop.owner(), "full.csv", full).status())
        .isEqualTo(200);
    String skus = skuState(shop.tenantId());
    String components = componentState(shop.tenantId());

    // Step 1: The required columns only, over bundles, barcodes and weights: nothing changes.
    StringBuilder slim = new StringBuilder("product_name,sku_code,sku_name\n");
    for (String line : full.split("\n")) {
      if (line.startsWith("product_name")) {
        continue;
      }
      String[] cells = line.split(",", -1);
      slim.append(cells[0]).append(',').append(cells[1]).append(',').append(cells[2]).append('\n');
    }
    long auditsBefore = catalogAudits(shop.tenantId());
    CatalogHttp.Result result =
        http.upload("/api/v1/catalog/import", shop.owner(), "slim.csv", slim.toString());
    assertThat(result.status()).as(result.raw()).isEqualTo(200);
    assertThat(result.body().path("skus_unchanged").asInt()).isEqualTo(1000);
    assertThat(result.body().path("skus_updated").asInt()).isZero();
    assertThat(result.body().path("bundles_replaced").asInt()).isZero();
    assertThat(skuState(shop.tenantId())).isEqualTo(skus);
    assertThat(componentState(shop.tenantId())).isEqualTo(components);
    assertThat(catalogAudits(shop.tenantId())).isEqualTo(auditsBefore + 1);
    assertThat(audits(shop.tenantId(), "CATALOG_IMPORTED")).isEqualTo(2);

    // Step 2: A present but empty cell clears the value; absent columns are still kept.
    CatalogHttp.Result cleared =
        http.upload(
            "/api/v1/catalog/import",
            shop.owner(),
            "clear.csv",
            "product_name,sku_code,sku_name,barcode\nProduct 0,SKU-0000,Item 0,\n");
    assertThat(cleared.body().path("skus_updated").asInt()).isEqualTo(1);
    assertThat(
            text(
                "SELECT coalesce(barcode, 'null') || '/' || weight_g FROM sku "
                    + "WHERE tenant_id = ? AND sku_code = 'SKU-0000'",
                shop.tenantId()))
        .isEqualTo("null/100");

    // Step 3: Components without an is_bundle column use the stored flag.
    CatalogHttp.Result relinked =
        http.upload(
            "/api/v1/catalog/import",
            shop.owner(),
            "relink.csv",
            "product_name,sku_code,sku_name,components\nBundles,BND-0000,Bundle 0,SKU-0001:3\n");
    assertThat(relinked.status()).as(relinked.raw()).isEqualTo(200);
    assertThat(relinked.body().path("bundles_replaced").asInt()).isEqualTo(1);
    assertThat(relinked.body().path("skus_updated").asInt()).isZero();
    assertThat(
            count(
                "SELECT count(*) FROM sku WHERE tenant_id = ? AND sku_code = 'BND-0000' "
                    + "AND updated_at > created_at",
                shop.tenantId()))
        .isEqualTo(1);
    CatalogHttp.Result notBundle =
        http.upload(
            "/api/v1/catalog/import",
            shop.owner(),
            "bad.csv",
            "product_name,sku_code,sku_name,components\nProduct 1,SKU-0001,Item 1,SKU-0002:1\n");
    assertThat(notBundle.status()).isEqualTo(422);
    assertThat(notBundle.body().path("errors").get(0).path("error").asString())
        .startsWith("BUNDLE_REQUIRED");

    // Step 4: Clearing is_bundle while the components column is absent keeps the list: rejected.
    CatalogHttp.Result keepList =
        http.upload(
            "/api/v1/catalog/import",
            shop.owner(),
            "flip.csv",
            "product_name,sku_code,sku_name,is_bundle\nBundles,BND-0001,Bundle 1,false\n");
    assertThat(keepList.status()).isEqualTo(422);
    assertThat(keepList.body().path("errors").get(0).path("column").asString())
        .isEqualTo("is_bundle");
  }

  @Test
  void badRowsAreReportedByLineAndNothingIsWritten() {
    CatalogHttp.Shop shop = http.shop();
    List<String> lines = new ArrayList<>(List.of(thousandRows().split("\n")));
    // Step 1: File line 7 is list index 6, line 912 is index 911 (line 1 is the header).
    lines.set(6, "Product 5,,No code,,,false,");
    lines.set(911, "Product 1,BAD-912,Bad weight,,heavy,false,");
    String csv = String.join("\n", lines) + "\n";

    CatalogHttp.Result result = http.upload("/api/v1/catalog/import", shop.owner(), "bad.csv", csv);

    // Step 2: 422 IMPORT_INVALID listing exactly rows 7 and 912, with the column.
    assertThat(result.status()).as(result.raw()).isEqualTo(422);
    assertThat(result.error()).isEqualTo("IMPORT_INVALID");
    List<Integer> rows = new ArrayList<>();
    List<String> columns = new ArrayList<>();
    for (JsonNode error : result.body().path("errors")) {
      rows.add(error.path("row").asInt());
      columns.add(error.path("column").asString());
      assertThat(error.path("error").asString()).isNotBlank();
    }
    assertThat(rows).containsExactly(7, 912);
    assertThat(columns).containsExactly("sku_code", "weight_g");

    // Step 3: Zero rows written: no product, SKU, component, warehouse, or audit row.
    for (String table : List.of("product", "sku", "sku_bundle_component", "warehouse")) {
      assertThat(count("SELECT count(*) FROM " + table + " WHERE tenant_id = ?", shop.tenantId()))
          .as(table)
          .isZero();
    }
    assertThat(catalogAudits(shop.tenantId())).isZero();
  }

  @Test
  void databaseRulesAreRowErrorsToo() {
    CatalogHttp.Shop shop = http.shop();
    String base =
        HEADER
            + "Kit,PART-1,Part one,,,false,\n"
            + "Kit,PART-2,Part two,,,false,\n"
            + "Kit,KIT-1,Kit,,,true,PART-1:2|PART-2:1\n";
    assertThat(http.upload("/api/v1/catalog/import", shop.owner(), "a.csv", base).status())
        .isEqualTo(200);
    UUID part2 =
        UUID.fromString(
            http.get("/api/v1/skus?q=PART-2", shop.owner())
                .body()
                .path("items")
                .get(0)
                .path("id")
                .asString());
    UUID warehouse =
        UUID.fromString(
            http.get("/api/v1/warehouses", shop.owner())
                .body()
                .path("items")
                .get(0)
                .path("id")
                .asString());
    CatalogHttp.seedInventory(shop.tenantId(), part2, warehouse);
    long auditsBefore = catalogAudits(shop.tenantId());

    // Step 1: Each database rule becomes a row error on the right line and column.
    String bad =
        HEADER
            + "Kit,PART-1,Part one,,,true,\n" // line 2: component of KIT-1 (not in this file)
            + "Kit,PART-2,Part two,,,true,\n" // line 3: inventory row, and a component of KIT-1
            + "Kit,KIT-2,Kit two,,,true,KIT-1:1\n" // line 4: KIT-1 is a bundle
            + "Kit,KIT-3,Kit three,,,true,NOPE:1\n" // line 5: unknown code
            + "Kit,KIT-4,Kit four,,,false,PART-1:1\n" // line 6: components on a non-bundle
            + "Kit,KIT-5,Kit five,,,true,KIT-5:1\n" // line 7: contains itself
            + "Kit,PART-9,\"Multi\nline\",,,false,\n" // lines 8-9: one record, control char
            + "Kit,PART-9,Duplicate,,,false,\n"; // line 10: duplicate code
    CatalogHttp.Result result = http.upload("/api/v1/catalog/import", shop.owner(), "b.csv", bad);
    assertThat(result.status()).as(result.raw()).isEqualTo(422);
    List<String> errors = new ArrayList<>();
    for (JsonNode error : result.body().path("errors")) {
      errors.add(
          error.path("row").asInt()
              + ":"
              + error.path("column").asString()
              + ":"
              + error.path("error").asString());
    }
    assertThat(errors)
        .hasSize(9)
        .anySatisfy(e -> assertThat(e).startsWith("2:is_bundle:NESTED_BUNDLE"))
        .anySatisfy(e -> assertThat(e).startsWith("3:is_bundle:BUNDLE_NOT_STOCKABLE"))
        .anySatisfy(e -> assertThat(e).startsWith("3:is_bundle:NESTED_BUNDLE"))
        .anySatisfy(e -> assertThat(e).startsWith("4:components:NESTED_BUNDLE"))
        .anySatisfy(e -> assertThat(e).startsWith("5:components:unknown sku_code NOPE"))
        .anySatisfy(e -> assertThat(e).startsWith("6:components:BUNDLE_REQUIRED"))
        .anySatisfy(e -> assertThat(e).startsWith("7:components:NESTED_BUNDLE"))
        .anySatisfy(e -> assertThat(e).startsWith("8:sku_name:must not contain control"))
        .anySatisfy(
            e -> assertThat(e).startsWith("10:sku_code:duplicate sku_code (first on row 8)"));
    assertThat(catalogAudits(shop.tenantId())).isEqualTo(auditsBefore);

    // Step 2: A valid second file updates, flips and re-links in one go.
    String next =
        HEADER
            + "Kit,PART-1,Part one renamed,,,false,\n"
            + "Kit,KIT-1,Kit,,,false,\n"
            + "Kit,PART-3,Part three,,,true,PART-1:1|PART-2:4\n";
    CatalogHttp.Result applied = http.upload("/api/v1/catalog/import", shop.owner(), "c.csv", next);
    assertThat(applied.status()).as(applied.raw()).isEqualTo(200);
    assertThat(applied.body().path("skus_updated").asInt()).isEqualTo(2);
    assertThat(applied.body().path("skus_created").asInt()).isEqualTo(1);
    assertThat(applied.body().path("bundles_replaced").asInt()).isEqualTo(2);
    assertThat(
            count(
                "SELECT count(*) FROM sku_bundle_component c JOIN sku s ON s.id = c.bundle_sku_id "
                    + "WHERE s.tenant_id = ? AND s.sku_code = 'PART-3'",
                shop.tenantId()))
        .isEqualTo(2);
  }

  @Test
  void headerAndEncodingErrors() {
    CatalogHttp.Shop shop = http.shop();
    CatalogHttp.Result header =
        http.upload(
            "/api/v1/catalog/import", shop.owner(), "h.csv", "product_name,code,sku_name\nA,B,C\n");
    assertThat(header.status()).isEqualTo(422);
    assertThat(header.body().path("errors").get(0).path("row").asInt()).isEqualTo(1);
    byte[] latin1 =
        "product_name,sku_code,sku_name\nCaf\u00e9,C-1,Caf\u00e9\n"
            .getBytes(StandardCharsets.ISO_8859_1);
    CatalogHttp.Result encoding =
        http.upload("/api/v1/catalog/import", shop.owner(), "l.csv", latin1);
    assertThat(encoding.status()).isEqualTo(422);
    assertThat(encoding.body().path("errors").get(0).path("error").asString()).contains("UTF-8");
    CatalogHttp.Result bom =
        http.upload(
            "/api/v1/catalog/import",
            shop.owner(),
            "bom.csv",
            "\uFEFFproduct_name,sku_code,sku_name\n\u0e40\u0e2a\u0e37\u0e49\u0e2d,TH-1,\u0e40\u0e2a\u0e37\u0e49\u0e2d\u0e22\u0e37\u0e14\n");
    assertThat(bom.status()).as(bom.raw()).isEqualTo(200);
  }

  /**
   * 1,000 data rows: 900 plain SKUs over 50 products, then 100 bundles. Bundle {@code i} sits at
   * file line {@code i + 2} and references two plain SKUs that come later in the file, so forward
   * references are covered.
   */
  static String thousandRows() {
    StringBuilder csv = new StringBuilder(HEADER);
    for (int i = 0; i < 100; i++) {
      csv.append("Bundles,BND-")
          .append(String.format("%04d", i))
          .append(",Bundle ")
          .append(i)
          .append(",,,true,SKU-")
          .append(String.format("%04d", i))
          .append(":2|SKU-")
          .append(String.format("%04d", i + 100))
          .append(":1\n");
    }
    for (int i = 0; i < 900; i++) {
      csv.append("Product ")
          .append(i % 49)
          .append(",SKU-")
          .append(String.format("%04d", i))
          .append(",Item ")
          .append(i)
          .append(',')
          .append(8_850_000_000_000L + i)
          .append(',')
          .append(100 + i)
          .append(",false,\n");
    }
    return csv.toString();
  }

  private static String skuState(UUID tenantId) {
    return text(
        "SELECT string_agg(sku_code || ':' || product_id || ':' || name || ':' "
            + "|| coalesce(barcode, '-') || ':' || coalesce(weight_g::text, '-') || ':' || is_bundle "
            + "|| ':' || updated_at, ',' ORDER BY sku_code) FROM sku WHERE tenant_id = ?",
        tenantId);
  }

  private static String componentState(UUID tenantId) {
    return text(
        "SELECT string_agg(bundle_sku_id || '>' || component_sku_id || 'x' || qty || '@' || updated_at,"
            + " ',' ORDER BY bundle_sku_id, component_sku_id) FROM sku_bundle_component "
            + "WHERE tenant_id = ?",
        tenantId);
  }

  private static String catalogSnapshot(UUID tenantId) {
    return count(
            "SELECT count(*) FROM sku WHERE tenant_id = ? AND updated_at = created_at", tenantId)
        + "/"
        + count("SELECT count(*) FROM product WHERE tenant_id = ?", tenantId)
        + "/"
        + count("SELECT count(*) FROM sku_bundle_component WHERE tenant_id = ?", tenantId);
  }
}
