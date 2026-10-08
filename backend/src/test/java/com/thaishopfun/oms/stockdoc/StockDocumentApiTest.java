package com.thaishopfun.oms.stockdoc;

import static com.thaishopfun.oms.catalog.CatalogHttp.auditsFor;
import static com.thaishopfun.oms.catalog.CatalogHttp.count;
import static com.thaishopfun.oms.catalog.CatalogHttp.execute;
import static org.assertj.core.api.Assertions.assertThat;

import com.thaishopfun.oms.catalog.CatalogHttp;
import com.thaishopfun.oms.catalog.CatalogIntegrationTest;
import com.thaishopfun.oms.invariant.VerifyInvariants;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
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

/** T08A stock documents and stock history over HTTP, as {@code oms_app} under FORCE RLS. */
@VerifyInvariants(scope = VerifyInvariants.Scope.STOCK_ONLY)
class StockDocumentApiTest extends CatalogIntegrationTest {

  private static final String DOCS = "/api/v1/stock-documents";

  @Test
  void draftLifecyclePostAndAudit() {
    CatalogHttp.Shop shop = http.shop();
    Sku mug = sku(shop, "MUG-" + UUID.randomUUID(), false);

    // Step 1: Create a RECEIVE draft; it is listed and readable.
    CatalogHttp.Result created =
        http.post(DOCS, shop.owner(), Map.of("type", "RECEIVE", "reference_no", "PO-1"));
    assertThat(created.status()).as(created.raw()).isEqualTo(201);
    String doc = created.body().path("id").asString();
    assertThat(created.body().path("status").asString()).isEqualTo("DRAFT");
    assertThat(http.get(DOCS + "?status=DRAFT", shop.owner()).body().path("total").asLong())
        .isEqualTo(1);

    // Step 2: Lines by code, default warehouse; update one, delete another.
    CatalogHttp.Result line =
        http.post(
            DOCS + "/" + doc + "/lines", shop.owner(), Map.of("sku_code", mug.code(), "qty", 3));
    assertThat(line.status()).as(line.raw()).isEqualTo(201);
    String lineId = line.body().path("id").asString();
    assertThat(line.body().path("warehouse_code").asString()).isEqualTo("MAIN");
    CatalogHttp.Result updated =
        http.put(
            DOCS + "/" + doc + "/lines/" + lineId,
            shop.owner(),
            Map.of("sku_id", mug.id(), "qty", 6));
    assertThat(updated.body().path("qty").asInt()).isEqualTo(6);
    String extra =
        http.post(DOCS + "/" + doc + "/lines", shop.owner(), Map.of("sku_id", mug.id(), "qty", 1))
            .body()
            .path("id")
            .asString();
    assertThat(http.delete(DOCS + "/" + doc + "/lines/" + extra, shop.owner()).status())
        .isEqualTo(204);
    assertThat(
            http.put(DOCS + "/" + doc, shop.owner(), Map.of("reference_no", "PO-2", "note", "Late"))
                .body()
                .path("reference_no")
                .asString())
        .isEqualTo("PO-2");

    // Step 3: Validation: unknown type, bundle line, unknown SKU, counted_qty off a COUNT.
    expect(http.post(DOCS, shop.owner(), Map.of("type", "GIFT")), 422, "VALIDATION_FAILED");
    Sku bundle = sku(shop, "BOX-" + UUID.randomUUID(), true);
    expect(
        http.post(
            DOCS + "/" + doc + "/lines", shop.owner(), Map.of("sku_id", bundle.id(), "qty", 1)),
        422,
        "BUNDLE_NOT_STOCKABLE");
    expect(
        http.post(DOCS + "/" + doc + "/lines", shop.owner(), Map.of("sku_code", "NOPE", "qty", 1)),
        422,
        "UNKNOWN_SKU");
    expect(
        http.post(
            DOCS + "/" + doc + "/lines",
            shop.owner(),
            Map.of("sku_id", mug.id(), "qty", 1, "counted_qty", 1)),
        422,
        "VALIDATION_FAILED");

    // Step 4: Post: 200 with the ledger movement; the document is frozen afterwards.
    CatalogHttp.Result posted = http.post(DOCS + "/" + doc + "/post", shop.owner(), null);
    assertThat(posted.status()).as(posted.raw()).isEqualTo(200);
    assertThat(posted.body().path("status").asString()).isEqualTo("POSTED");
    assertThat(posted.body().path("movements").get(0).path("reason").asString())
        .isEqualTo("RECEIVE");
    assertThat(posted.body().path("movements").get(0).path("line_id").asString()).isEqualTo(lineId);
    JsonNode read = http.get(DOCS + "/" + doc, shop.owner()).body();
    assertThat(read.path("status").asString()).isEqualTo("POSTED");
    assertThat(read.path("lines").get(0).path("on_hand").asInt()).isEqualTo(6);
    expect(
        http.post(DOCS + "/" + doc + "/lines", shop.owner(), Map.of("sku_id", mug.id(), "qty", 1)),
        409,
        "DOCUMENT_NOT_DRAFT");
    expect(http.delete(DOCS + "/" + doc, shop.owner()), 409, "DOCUMENT_NOT_DRAFT");
    assertThat(http.get("/api/v1/skus/" + mug.id(), shop.owner()).body().path("on_hand").asInt())
        .isEqualTo(6);

    // Step 5: Every change wrote one audit row in its own transaction. No note text is stored.
    assertThat(auditsFor(shop.tenantId(), "STOCK_DOCUMENT_CREATED", doc)).isEqualTo(1);
    assertThat(auditsFor(shop.tenantId(), "STOCK_DOCUMENT_UPDATED", doc)).isEqualTo(1);
    assertThat(auditsFor(shop.tenantId(), "STOCK_DOCUMENT_POSTED", doc)).isEqualTo(1);
    assertThat(auditsFor(shop.tenantId(), "STOCK_DOCUMENT_LINE_ADDED", lineId)).isEqualTo(1);
    assertThat(auditsFor(shop.tenantId(), "STOCK_DOCUMENT_LINE_UPDATED", lineId)).isEqualTo(1);
    assertThat(auditsFor(shop.tenantId(), "STOCK_DOCUMENT_LINE_DELETED", extra)).isEqualTo(1);
    assertThat(
            count(
                "SELECT count(*) FROM audit_log WHERE tenant_id = ? AND \"after\"::text LIKE ?",
                shop.tenantId(),
                "%Late%"))
        .isZero();

    // Step 6: A draft can be deleted with its lines.
    String draft =
        http.post(DOCS, shop.owner(), Map.of("type", "WRITE_OFF")).body().path("id").asString();
    http.post(DOCS + "/" + draft + "/lines", shop.owner(), Map.of("sku_id", mug.id(), "qty", 1));
    assertThat(http.delete(DOCS + "/" + draft, shop.owner()).status()).isEqualTo(204);
    assertThat(http.get(DOCS + "/" + draft, shop.owner()).status()).isEqualTo(404);
    assertThat(auditsFor(shop.tenantId(), "STOCK_DOCUMENT_DELETED", draft)).isEqualTo(1);
  }

  @Test
  void staffPostsReceiveOnlyAndGraceIsReadOnly() {
    CatalogHttp.Shop shop = http.shop();
    Sku mug = sku(shop, "MUG-" + UUID.randomUUID(), false);
    String staff = http.member(shop, "STAFF");
    String admin = http.member(shop, "ADMIN");

    // Step 1: STAFF edits drafts and posts a RECEIVE.
    String receive = draft(shop, staff, "RECEIVE", mug, 5, null);
    CatalogHttp.Result posted = http.post(DOCS + "/" + receive + "/post", staff, null);
    assertThat(posted.status()).as(posted.raw()).isEqualTo(200);

    // Step 2: Every other type and every void needs OWNER or ADMIN. The drafts stay DRAFT.
    for (String type : List.of("OPENING", "ADJUSTMENT", "COUNT", "WRITE_OFF")) {
      String doc = draft(shop, staff, type, mug, 1, "ADJUSTMENT".equals(type) ? "FOUND" : null);
      expect(http.post(DOCS + "/" + doc + "/post", staff, null), 403, "FORBIDDEN");
      assertThat(http.get(DOCS + "/" + doc, staff).body().path("status").asString())
          .isEqualTo("DRAFT");
    }
    expect(http.post(DOCS + "/" + receive + "/void", staff, null), 403, "FORBIDDEN");
    String adjust = draft(shop, staff, "ADJUSTMENT", mug, 1, "FOUND");
    assertThat(http.post(DOCS + "/" + adjust + "/post", admin, null).status()).isEqualTo(200);
    assertThat(http.post(DOCS + "/" + receive + "/void", admin, null).status()).isEqualTo(200);

    // Step 3: GRACE reads, and every write is 403 ENTITLEMENT_GRACE before the controller.
    String graceShop = "shop-" + UUID.randomUUID();
    String grace = CatalogHttp.token("grace-" + UUID.randomUUID(), graceShop, "OWNER", "GRACE");
    assertThat(http.get(DOCS, grace).status()).isEqualTo(200);
    expect(http.post(DOCS, grace, Map.of("type", "RECEIVE")), 403, "ENTITLEMENT_GRACE");
    expect(
        http.post(DOCS + "/" + UUID.randomUUID() + "/post", grace, null), 403, "ENTITLEMENT_GRACE");
    expect(
        http.post(DOCS + "/" + UUID.randomUUID() + "/void", grace, null), 403, "ENTITLEMENT_GRACE");
  }

  @Test
  void otherTenantsDocumentsAndHistoryAreNotFound() {
    CatalogHttp.Shop a = http.shop();
    CatalogHttp.Shop b = http.shop();
    Sku mug = sku(a, "MUG-" + UUID.randomUUID(), false);
    String doc = draft(a, a.owner(), "RECEIVE", mug, 2, null);

    // Step 1: B sees nothing of A: read, list, edit, post, void, history are all 404 or empty.
    expect(http.get(DOCS + "/" + doc, b.owner()), 404, "NOT_FOUND");
    assertThat(http.get(DOCS, b.owner()).body().path("total").asLong()).isZero();
    expect(
        http.post(DOCS + "/" + doc + "/lines", b.owner(), Map.of("sku_id", mug.id(), "qty", 1)),
        404,
        "NOT_FOUND");
    expect(http.post(DOCS + "/" + doc + "/post", b.owner(), null), 404, "NOT_FOUND");
    assertThat(http.post(DOCS + "/" + doc + "/post", a.owner(), null).status()).isEqualTo(200);
    expect(http.post(DOCS + "/" + doc + "/void", b.owner(), null), 404, "NOT_FOUND");
    expect(http.get(history(mug.id()), b.owner()), 404, "NOT_FOUND");
    assertThat(http.get(history(mug.id()), a.owner()).body().path("items").size()).isEqualTo(1);
    expect(http.get(DOCS + "/not-a-uuid", a.owner()), 404, "NOT_FOUND");
  }

  @Test
  void doubleClickPostsOnceAndBelowReservedNamesTheRow() throws Exception {
    CatalogHttp.Shop shop = http.shop();
    Sku mug = sku(shop, "MUG-" + UUID.randomUUID(), false);
    String receive = draft(shop, shop.owner(), "RECEIVE", mug, 5, null);

    // Step 1: Two posts at once: both 200 with the same body, one ledger row.
    List<CatalogHttp.Result> results =
        together(2, () -> http.post(DOCS + "/" + receive + "/post", shop.owner(), null));
    assertThat(results).allSatisfy(result -> assertThat(result.status()).isEqualTo(200));
    assertThat(results.get(0).body()).isEqualTo(results.get(1).body());
    assertThat(http.post(DOCS + "/" + receive + "/post", shop.owner(), null).body())
        .isEqualTo(results.get(0).body());
    assertThat(ledger(shop, mug, "RECEIVE")).isEqualTo(1);

    // Step 2: 4 of the 5 are held. Writing off 2 is 422 BELOW_RESERVED with the row's numbers.
    execute(
        "UPDATE inventory SET reserved = 4 WHERE tenant_id = ? AND sku_id = ?",
        shop.tenantId(),
        UUID.fromString(mug.id()));
    String writeOff = draft(shop, shop.owner(), "WRITE_OFF", mug, 2, null);
    CatalogHttp.Result below = http.post(DOCS + "/" + writeOff + "/post", shop.owner(), null);
    expect(below, 422, "BELOW_RESERVED");
    JsonNode problem = below.body().path("errors").get(0);
    assertThat(problem.path("sku_id").asString()).isEqualTo(mug.id());
    assertThat(problem.path("on_hand").asInt()).isEqualTo(5);
    assertThat(problem.path("reserved").asInt()).isEqualTo(4);
    assertThat(problem.path("delta").asInt()).isEqualTo(-2);
    assertThat(problem.path("line_id").asString()).isNotBlank();

    // Step 3: The adjustment AC: no reason is REASON_REQUIRED, listed per line.
    String adjust = draft(shop, shop.owner(), "ADJUSTMENT", mug, -1, null);
    CatalogHttp.Result noReason = http.post(DOCS + "/" + adjust + "/post", shop.owner(), null);
    expect(noReason, 422, "REASON_REQUIRED");
    assertThat(noReason.body().path("errors").get(0).path("error").asString())
        .isEqualTo("REASON_REQUIRED");
    assertThat(ledger(shop, mug, "DAMAGE_WRITE_OFF") + ledger(shop, mug, "ADJUST_OUT")).isZero();
    execute(
        "UPDATE inventory SET reserved = 0 WHERE tenant_id = ? AND sku_id = ?",
        shop.tenantId(),
        UUID.fromString(mug.id()));
  }

  @Test
  void countStartsOnceAndPostsTheCorrection() {
    CatalogHttp.Shop shop = http.shop();
    Sku mug = sku(shop, "MUG-" + UUID.randomUUID(), false);
    post(shop, draft(shop, shop.owner(), "OPENING", mug, 10, null));

    // Step 1: A count line, start, snapshot. Starting again is 409.
    String count =
        http.post(DOCS, shop.owner(), Map.of("type", "COUNT")).body().path("id").asString();
    String line =
        http.post(DOCS + "/" + count + "/lines", shop.owner(), Map.of("sku_id", mug.id()))
            .body()
            .path("id")
            .asString();
    expect(http.post(DOCS + "/" + count + "/post", shop.owner(), null), 422, "COUNT_NOT_STARTED");
    JsonNode started = http.post(DOCS + "/" + count + "/start-count", shop.owner(), null).body();
    assertThat(started.path("count_started_at").isNull()).isFalse();
    assertThat(started.path("lines").get(0).path("system_qty_at_start").asInt()).isEqualTo(10);
    expect(
        http.post(DOCS + "/" + count + "/start-count", shop.owner(), null),
        409,
        "COUNT_ALREADY_STARTED");
    expect(
        http.post(DOCS + "/" + count + "/lines", shop.owner(), Map.of("sku_id", mug.id())),
        422,
        "DUPLICATE_LINE");

    // Step 2: No counted qty yet is 422; with 7 counted the correction is -3.
    expect(
        http.post(DOCS + "/" + count + "/post", shop.owner(), null), 422, "COUNTED_QTY_REQUIRED");
    http.put(
        DOCS + "/" + count + "/lines/" + line,
        shop.owner(),
        Map.of("sku_id", mug.id(), "counted_qty", 7));
    CatalogHttp.Result posted = http.post(DOCS + "/" + count + "/post", shop.owner(), null);
    assertThat(posted.status()).as(posted.raw()).isEqualTo(200);
    assertThat(posted.body().path("movements").get(0).path("delta_on_hand").asInt()).isEqualTo(-3);
    assertThat(
            http.get(DOCS + "/" + count, shop.owner())
                .body()
                .path("lines")
                .get(0)
                .path("qty")
                .asInt())
        .isEqualTo(-3);
  }

  @Test
  void historyFiltersPagesAndLinksEveryRefType() {
    CatalogHttp.Shop shop = http.shop();
    Sku mug = sku(shop, "MUG-" + UUID.randomUUID(), false);

    // Step 1: Opening 10 (reference OB-1), receive 5, adjust -2.
    String opening =
        http.post(DOCS, shop.owner(), Map.of("type", "OPENING", "reference_no", "OB-1"))
            .body()
            .path("id")
            .asString();
    http.post(DOCS + "/" + opening + "/lines", shop.owner(), Map.of("sku_id", mug.id(), "qty", 10));
    post(shop, opening);
    post(shop, draft(shop, shop.owner(), "RECEIVE", mug, 5, null));
    post(shop, draft(shop, shop.owner(), "ADJUSTMENT", mug, -2, "LOST"));

    // Step 2: A reservation and a return restock, seeded the way T08 and T13 write them.
    UUID warehouse =
        UUID.fromString(
            CatalogHttp.text(
                "SELECT warehouse_id::text FROM inventory WHERE tenant_id = ? AND sku_id = ?",
                shop.tenantId(),
                UUID.fromString(mug.id())));
    UUID reservation = UUID.randomUUID();
    UUID group = UUID.randomUUID();
    execute(
        "UPDATE inventory SET reserved = reserved + 1 WHERE tenant_id = ? AND sku_id = ?",
        shop.tenantId(),
        UUID.fromString(mug.id()));
    execute(
        "INSERT INTO stock_reservation (id, tenant_id, owner_type, owner_ref, sku_id, warehouse_id,"
            + " qty, status, reservation_group_id) VALUES (?, ?, 'ORDER', 'ord-77', ?, ?, 1,"
            + " 'ACTIVE', ?)",
        reservation,
        shop.tenantId(),
        UUID.fromString(mug.id()),
        warehouse,
        group);
    seedLedger(shop, mug, warehouse, 0, 1, "RESERVE", "stock_reservation", reservation);
    UUID returnLine = UUID.randomUUID();
    execute(
        "UPDATE inventory SET on_hand = on_hand + 1 WHERE tenant_id = ? AND sku_id = ?",
        shop.tenantId(),
        UUID.fromString(mug.id()));
    seedLedger(shop, mug, warehouse, 1, 0, "RETURN_RESTOCK", "return_line", returnLine);

    // Step 3: Keyset pages of 2, newest first, cover all five entries exactly once.
    List<JsonNode> all = new ArrayList<>();
    String cursor = null;
    do {
      JsonNode page =
          http.get(
                  history(mug.id()) + "?limit=2" + (cursor == null ? "" : "&cursor=" + cursor),
                  shop.owner())
              .body();
      page.path("items").forEach(all::add);
      cursor = page.path("next_cursor").isNull() ? null : page.path("next_cursor").asString();
    } while (cursor != null);
    assertThat(all)
        .extracting(e -> e.path("reason").asString())
        .containsExactly("RETURN_RESTOCK", "RESERVE", "ADJUST_OUT", "RECEIVE", "OPENING_BALANCE");
    assertThat(all.get(0).path("on_hand_after").asInt()).isEqualTo(14);
    assertThat(all.get(0).path("reserved_after").asInt()).isEqualTo(1);
    assertThat(all.get(3).path("on_hand_after").asInt()).isEqualTo(15);

    // Step 4: Link targets per ref_type.
    JsonNode openingLink = all.get(4).path("link");
    assertThat(openingLink.path("kind").asString()).isEqualTo("stock_document");
    assertThat(openingLink.path("document_id").asString()).isEqualTo(opening);
    assertThat(openingLink.path("reference_no").asString()).isEqualTo("OB-1");
    JsonNode reserveLink = all.get(1).path("link");
    assertThat(reserveLink.path("kind").asString()).isEqualTo("reservation");
    assertThat(reserveLink.path("order_ref").asString()).isEqualTo("ord-77");
    assertThat(reserveLink.path("reservation_group_id").asString()).isEqualTo(group.toString());
    assertThat(all.get(0).path("link").path("return_line_id").asString())
        .isEqualTo(returnLine.toString());

    // Step 5: Filters: reason, dates (Bangkok days), warehouse; bad input is 422.
    assertThat(items(shop, mug, "?reason=RECEIVE")).isEqualTo(1);
    String tomorrow = LocalDate.now(TimeFilter.SHOP_ZONE).plusDays(1).toString();
    String yesterday = LocalDate.now(TimeFilter.SHOP_ZONE).minusDays(1).toString();
    assertThat(items(shop, mug, "?from=" + tomorrow)).isZero();
    assertThat(items(shop, mug, "?to=" + yesterday)).isZero();
    assertThat(items(shop, mug, "?from=" + yesterday + "&to=" + tomorrow)).isEqualTo(5);
    assertThat(items(shop, mug, "?warehouse_id=" + UUID.randomUUID())).isZero();
    expect(http.get(history(mug.id()) + "?reason=GIFT", shop.owner()), 422, "VALIDATION_FAILED");
    expect(http.get(history(mug.id()) + "?cursor=%21%21", shop.owner()), 422, "VALIDATION_FAILED");
    Sku bundle = sku(shop, "BOX-" + UUID.randomUUID(), true);
    expect(http.get(history(bundle.id()), shop.owner()), 422, "BUNDLE_NOT_STOCKABLE");
    expect(http.get(history(UUID.randomUUID().toString()), shop.owner()), 404, "NOT_FOUND");
  }

  // ---- helpers -----------------------------------------------------------------------------

  private record Sku(String id, String code) {}

  private Sku sku(CatalogHttp.Shop shop, String code, boolean bundle) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("product_name", "Stock");
    body.put("sku_code", code);
    body.put("name", code);
    body.put("is_bundle", bundle);
    CatalogHttp.Result created = http.post("/api/v1/skus", shop.owner(), body);
    assertThat(created.status()).as(created.raw()).isEqualTo(201);
    return new Sku(created.body().path("id").asString(), code);
  }

  private String draft(
      CatalogHttp.Shop shop, String token, String type, Sku sku, int qty, String reason) {
    CatalogHttp.Result created = http.post(DOCS, token, Map.of("type", type));
    assertThat(created.status()).as(created.raw()).isEqualTo(201);
    String doc = created.body().path("id").asString();
    Map<String, Object> line = new HashMap<>();
    line.put("sku_id", sku.id());
    if ("COUNT".equals(type)) {
      line.put("counted_qty", qty);
    } else {
      line.put("qty", qty);
    }
    if (reason != null) {
      line.put("reason_code", reason);
    }
    CatalogHttp.Result added = http.post(DOCS + "/" + doc + "/lines", token, line);
    assertThat(added.status()).as(added.raw()).isEqualTo(201);
    return doc;
  }

  private void post(CatalogHttp.Shop shop, String doc) {
    CatalogHttp.Result posted = http.post(DOCS + "/" + doc + "/post", shop.owner(), null);
    assertThat(posted.status()).as(posted.raw()).isEqualTo(200);
  }

  private int items(CatalogHttp.Shop shop, Sku sku, String query) {
    CatalogHttp.Result page = http.get(history(sku.id()) + query, shop.owner());
    assertThat(page.status()).as(page.raw()).isEqualTo(200);
    return page.body().path("items").size();
  }

  private static String history(String skuId) {
    return "/api/v1/skus/" + skuId + "/stock-history";
  }

  private static long ledger(CatalogHttp.Shop shop, Sku sku, String reason) {
    return count(
        "SELECT count(*) FROM inventory_ledger WHERE tenant_id = ? AND sku_id = ? AND reason = ?",
        shop.tenantId(),
        UUID.fromString(sku.id()),
        reason);
  }

  private static void seedLedger(
      CatalogHttp.Shop shop,
      Sku sku,
      UUID warehouse,
      int onHand,
      int reserved,
      String reason,
      String refType,
      UUID refId) {
    long seq =
        count(
            """
            UPDATE inventory SET ledger_seq = ledger_seq + 1
            WHERE tenant_id = ? AND sku_id = ? AND warehouse_id = ?
            RETURNING ledger_seq
            """,
            shop.tenantId(),
            UUID.fromString(sku.id()),
            warehouse);
    execute(
        "INSERT INTO inventory_ledger (id, tenant_id, sku_id, warehouse_id, delta_on_hand,"
            + " delta_reserved, reason, ref_type, ref_id, actor, ledger_seq) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?,"
            + " 'test', ?)",
        UUID.randomUUID(),
        shop.tenantId(),
        UUID.fromString(sku.id()),
        warehouse,
        onHand,
        reserved,
        reason,
        refType,
        refId,
        seq);
  }

  private static void expect(CatalogHttp.Result result, int status, String error) {
    assertThat(result.status()).as(result.raw()).isEqualTo(status);
    assertThat(result.error()).as(result.raw()).isEqualTo(error);
  }

  private static <T> List<T> together(int n, java.util.concurrent.Callable<T> call)
      throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(n);
    CyclicBarrier barrier = new CyclicBarrier(n);
    try {
      List<Future<T>> futures = new ArrayList<>();
      for (int i = 0; i < n; i++) {
        futures.add(
            pool.submit(
                () -> {
                  barrier.await();
                  return call.call();
                }));
      }
      List<T> results = new ArrayList<>();
      for (Future<T> future : futures) {
        results.add(future.get(60, TimeUnit.SECONDS));
      }
      return results;
    } finally {
      pool.shutdownNow();
    }
  }
}
