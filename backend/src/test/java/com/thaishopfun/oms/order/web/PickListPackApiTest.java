package com.thaishopfun.oms.order.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.thaishopfun.oms.catalog.CatalogHttp;
import com.thaishopfun.oms.fulfillment.FulfillmentTestSupport;
import com.thaishopfun.oms.order.OrderRecipientRepository;
import com.thaishopfun.oms.order.OrderStateMachine;
import com.thaishopfun.oms.order.OrderStatusHistoryRepository;
import com.thaishopfun.oms.order.SalesOrder;
import com.thaishopfun.oms.order.SalesOrderRepository;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;

class PickListPackApiTest extends OrderIntegrationTest {

  @Autowired SalesOrderRepository orders;
  @Autowired OrderRecipientRepository recipients;
  @Autowired OrderStatusHistoryRepository history;
  @Autowired OrderStateMachine stateMachine;
  @Autowired PlatformTransactionManager transactions;

  private OrderFixture fixture;

  @BeforeEach
  void setup() {
    fixture = new OrderFixture(orders, recipients, history, transactions);
  }

  @Test
  void pickListExcludesHeldOrdersAndMovesReadyOnesToPicking() throws Exception {
    CatalogHttp.Shop httpShop = http.catalog().shop();
    OrderFixture.Shop shop = OrderFixture.shopFor(httpShop);
    SalesOrder ready = fixture.insert(shop, "ORD-PICK-1", "READY_TO_PICK", "NONE");
    fixture.insert(shop, "ORD-HOLD", "UNFULFILLED", "OUT_OF_STOCK");
    FulfillmentTestSupport.SkuSeed sku =
        FulfillmentTestSupport.seedSku(shop.tenantId(), "TEE-BLK-M", "8850000000010");
    FulfillmentTestSupport.addLine(ready, sku.skuId(), 2);

    CatalogHttp.Result candidates = http.get("/api/v1/pick-lists/candidates", httpShop.owner());
    assertThat(candidates.status()).isEqualTo(200);
    assertThat(candidates.body().path("items")).hasSize(1);

    CatalogHttp.Result created =
        http.post(
            "/api/v1/pick-lists",
            httpShop.owner(),
            Map.of("order_ids", List.of(ready.id().toString())));
    assertThat(created.status()).isEqualTo(200);
    String pickListId = created.body().path("id").asString();
    assertThat(CatalogHttp.auditsFor(shop.tenantId(), "PICK_LIST_CREATED", pickListId))
        .isEqualTo(1);

    CatalogHttp.Result orderAfter =
        http.get(OrderHttp.ordersPath("/" + ready.id()), httpShop.owner());
    assertThat(orderAfter.body().path("fulfillment_status").asString()).isEqualTo("PICKING");

    byte[] pdf = http.getPdf("/api/v1/pick-lists/" + pickListId + "/pdf", httpShop.owner());
    assertThat(new String(pdf, 0, 4, StandardCharsets.US_ASCII)).isEqualTo("%PDF");
  }

  @Test
  void packScanAcceptsSkuOrEanAndBlocksOverflow() throws Exception {
    CatalogHttp.Shop httpShop = http.catalog().shop();
    OrderFixture.Shop shop = OrderFixture.shopFor(httpShop);
    SalesOrder order = fixture.insert(shop, "ORD-PACK", "READY_TO_PICK", "NONE");
    FulfillmentTestSupport.SkuSeed sku =
        FulfillmentTestSupport.seedSku(shop.tenantId(), "TEE-RED-L", "8850000000028");
    FulfillmentTestSupport.addLine(order, sku.skuId(), 2);

    http.post("/api/v1/pick-lists", httpShop.owner(), Map.of("order_ids", List.of(order.id())));

    CatalogHttp.Result session = http.get("/api/v1/pack/orders/" + order.id(), httpShop.owner());
    assertThat(session.status()).isEqualTo(200);
    assertThat(session.body().path("recipient").path("phone").asString())
        .isEqualTo(OrderFixture.PHONE);
    assertThat(
            CatalogHttp.auditsFor(shop.tenantId(), "ORDER_PACK_PII_VIEWED", order.id().toString()))
        .isEqualTo(1);

    CatalogHttp.Result sessionAgain =
        http.get("/api/v1/pack/orders/" + order.id(), httpShop.owner());
    assertThat(sessionAgain.status()).isEqualTo(200);
    assertThat(
            CatalogHttp.auditsFor(shop.tenantId(), "ORDER_PACK_PII_VIEWED", order.id().toString()))
        .isEqualTo(1);

    CatalogHttp.Result detailBefore =
        http.get(OrderHttp.ordersPath("/" + order.id()), httpShop.owner());
    assertThat(detailBefore.body().path("recipient").path("phone_masked").asString())
        .isEqualTo("***-***-5678");

    assertScan(httpShop.owner(), order.id(), sku.skuCode(), 1, 2, false);
    assertScan(httpShop.owner(), order.id(), sku.barcode(), 2, 2, true);

    CatalogHttp.Result overflow =
        http.post(
            "/api/v1/pack/orders/" + order.id() + "/scans",
            httpShop.owner(),
            Map.of("scan", sku.barcode()));
    assertThat(overflow.status()).isEqualTo(409);
    assertThat(overflow.error()).isEqualTo("PACK_SCAN_OVERFLOW");

    CatalogHttp.Result wrong =
        http.post(
            "/api/v1/pack/orders/" + order.id() + "/scans",
            httpShop.owner(),
            Map.of("scan", "UNKNOWN-CODE"));
    assertThat(wrong.status()).isEqualTo(422);
    assertThat(wrong.error()).isEqualTo("PACK_SCAN_UNKNOWN");

    CatalogHttp.Result complete =
        http.post("/api/v1/pack/orders/" + order.id() + "/complete", httpShop.owner(), Map.of());
    assertThat(complete.status()).isEqualTo(200);
    assertThat(complete.body().path("fulfillment_status").asString()).isEqualTo("PACKED");
  }

  @Test
  void packSessionAfterCompleteReturnsPackedWithoutError() throws Exception {
    CatalogHttp.Shop httpShop = http.catalog().shop();
    OrderFixture.Shop shop = OrderFixture.shopFor(httpShop);
    SalesOrder order = fixture.insert(shop, "ORD-PACKED-SESSION", "READY_TO_PICK", "NONE");
    FulfillmentTestSupport.SkuSeed sku =
        FulfillmentTestSupport.seedSku(shop.tenantId(), "TEE-PACKED", "8850000000036");
    FulfillmentTestSupport.addLine(order, sku.skuId(), 1);

    http.post("/api/v1/pick-lists", httpShop.owner(), Map.of("order_ids", List.of(order.id())));
    assertThat(http.get("/api/v1/pack/orders/" + order.id(), httpShop.owner()).status())
        .isEqualTo(200);
    assertScan(httpShop.owner(), order.id(), sku.skuCode(), 1, 1, true);
    assertThat(
            http.post("/api/v1/pack/orders/" + order.id() + "/complete", httpShop.owner(), Map.of())
                .status())
        .isEqualTo(200);

    CatalogHttp.Result session = http.get("/api/v1/pack/orders/" + order.id(), httpShop.owner());
    assertThat(session.status()).isEqualTo(200);
    assertThat(session.body().path("fulfillment_status").asString()).isEqualTo("PACKED");
    assertThat(
            CatalogHttp.auditsFor(shop.tenantId(), "ORDER_PACK_PII_VIEWED", order.id().toString()))
        .isEqualTo(1);
  }

  @Test
  void pickListDetailUsesOrderLineNameForPickLines() throws Exception {
    CatalogHttp.Shop httpShop = http.catalog().shop();
    OrderFixture.Shop shop = OrderFixture.shopFor(httpShop);
    SalesOrder order = fixture.insert(shop, "ORD-PDF-NAME", "READY_TO_PICK", "NONE");
    FulfillmentTestSupport.SkuSeed sku =
        FulfillmentTestSupport.seedSku(shop.tenantId(), "SKU-PDF", "8850000000044");
    FulfillmentTestSupport.addLine(order, sku.skuId(), 1);

    CatalogHttp.Result created =
        http.post("/api/v1/pick-lists", httpShop.owner(), Map.of("order_ids", List.of(order.id())));
    String pickListId = created.body().path("id").asString();

    CatalogHttp.Result detail = http.get("/api/v1/pick-lists/" + pickListId, httpShop.owner());
    assertThat(detail.status()).isEqualTo(200);
    assertThat(detail.body().path("pick_lines")).hasSize(1);
    assertThat(detail.body().path("pick_lines").get(0).path("name").asString()).isEqualTo("Line");
    assertThat(detail.body().path("pick_lines").get(0).path("sku_code").asString())
        .isEqualTo("SKU-PDF");
  }

  @Test
  void newPickingStintClearsPackProgress() throws Exception {
    CatalogHttp.Shop httpShop = http.catalog().shop();
    OrderFixture.Shop shop = OrderFixture.shopFor(httpShop);
    SalesOrder order = fixture.insert(shop, "ORD-RE-PICK", "READY_TO_PICK", "NONE");
    FulfillmentTestSupport.SkuSeed sku =
        FulfillmentTestSupport.seedSku(shop.tenantId(), "TEE-RE", "8850000000051");
    FulfillmentTestSupport.addLine(order, sku.skuId(), 2);

    http.post("/api/v1/pick-lists", httpShop.owner(), Map.of("order_ids", List.of(order.id())));
    assertScan(httpShop.owner(), order.id(), sku.skuCode(), 1, 2, false);
    assertScan(httpShop.owner(), order.id(), sku.skuCode(), 2, 2, true);
    assertThat(
            http.post("/api/v1/pack/orders/" + order.id() + "/complete", httpShop.owner(), Map.of())
                .status())
        .isEqualTo(200);

    fixture.inTenant(
        shop.tenantId(),
        () -> {
          SalesOrder fresh = orders.findById(order.id()).orElseThrow();
          stateMachine.applyFulfillmentStatus(
              fresh,
              "READY_TO_PICK",
              "test reset",
              "test",
              new OrderStateMachine.GuardContext(false, true, Instant.now()));
          return null;
        });

    http.post("/api/v1/pick-lists", httpShop.owner(), Map.of("order_ids", List.of(order.id())));

    CatalogHttp.Result session = http.get("/api/v1/pack/orders/" + order.id(), httpShop.owner());
    assertThat(session.status()).isEqualTo(200);
    assertThat(session.body().path("fulfillment_status").asString()).isEqualTo("PICKING");
    assertThat(session.body().path("lines").get(0).path("packed_qty").asInt()).isZero();
  }

  @Test
  void packScanFillsNextLineWhenSameSkuOnMultipleLines() throws Exception {
    CatalogHttp.Shop httpShop = http.catalog().shop();
    OrderFixture.Shop shop = OrderFixture.shopFor(httpShop);
    SalesOrder order = fixture.insert(shop, "ORD-DUP-SKU", "READY_TO_PICK", "NONE");
    FulfillmentTestSupport.SkuSeed sku =
        FulfillmentTestSupport.seedSku(shop.tenantId(), "DUP-SKU", "8850000000099");
    FulfillmentTestSupport.addLine(order, sku.skuId(), 1, "L1");
    FulfillmentTestSupport.addLine(order, sku.skuId(), 1, "L2");
    http.post("/api/v1/pick-lists", httpShop.owner(), Map.of("order_ids", List.of(order.id())));

    assertScan(httpShop.owner(), order.id(), sku.skuCode(), 1, 1, false);
    CatalogHttp.Result second =
        http.post(
            "/api/v1/pack/orders/" + order.id() + "/scans",
            httpShop.owner(),
            Map.of("scan", sku.skuCode()));
    assertThat(second.status()).isEqualTo(200);
    assertThat(second.body().path("packed_qty").asInt()).isEqualTo(1);
    assertThat(second.body().path("required_qty").asInt()).isEqualTo(1);
    assertThat(second.body().path("complete").asBoolean()).isTrue();
  }

  @Test
  void packScanMismatchWhenSkuNotOnOrder() throws Exception {
    CatalogHttp.Shop httpShop = http.catalog().shop();
    OrderFixture.Shop shop = OrderFixture.shopFor(httpShop);
    SalesOrder order = fixture.insert(shop, "ORD-MISMATCH", "READY_TO_PICK", "NONE");
    FulfillmentTestSupport.SkuSeed onOrder =
        FulfillmentTestSupport.seedSku(shop.tenantId(), "ON-ORDER", "111");
    FulfillmentTestSupport.SkuSeed other =
        FulfillmentTestSupport.seedSku(shop.tenantId(), "OTHER-SKU", "222");
    FulfillmentTestSupport.addLine(order, onOrder.skuId(), 1);
    http.post("/api/v1/pick-lists", httpShop.owner(), Map.of("order_ids", List.of(order.id())));

    CatalogHttp.Result mismatch =
        http.post(
            "/api/v1/pack/orders/" + order.id() + "/scans",
            httpShop.owner(),
            Map.of("scan", other.skuCode()));
    assertThat(mismatch.status()).isEqualTo(422);
    assertThat(mismatch.error()).isEqualTo("PACK_SCAN_MISMATCH");
  }

  private void assertScan(
      String token, UUID orderId, String scan, int packed, int required, boolean complete) {
    CatalogHttp.Result response =
        http.post("/api/v1/pack/orders/" + orderId + "/scans", token, Map.of("scan", scan));
    assertThat(response.status()).isEqualTo(200);
    assertThat(response.body().path("packed_qty").asInt()).isEqualTo(packed);
    assertThat(response.body().path("required_qty").asInt()).isEqualTo(required);
    assertThat(response.body().path("complete").asBoolean()).isEqualTo(complete);
  }
}
