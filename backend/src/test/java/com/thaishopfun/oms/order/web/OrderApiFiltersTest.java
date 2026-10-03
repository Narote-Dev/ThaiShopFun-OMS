package com.thaishopfun.oms.order.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.thaishopfun.oms.auth.AuthTestSupport;
import com.thaishopfun.oms.auth.UuidV7;
import com.thaishopfun.oms.catalog.CatalogHttp;
import com.thaishopfun.oms.order.OrderRecipientRepository;
import com.thaishopfun.oms.order.OrderStatusHistoryRepository;
import com.thaishopfun.oms.order.SalesOrder;
import com.thaishopfun.oms.order.SalesOrderRepository;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

class OrderApiFiltersTest extends OrderIntegrationTest {

  @Autowired SalesOrderRepository orders;
  @Autowired OrderRecipientRepository recipients;
  @Autowired OrderStatusHistoryRepository history;
  @Autowired PlatformTransactionManager transactions;
  @Autowired JdbcTemplate jdbc;

  private OrderFixture fixture;

  @BeforeEach
  void setup() {
    fixture = new OrderFixture(orders, recipients, history, transactions);
  }

  @Test
  void eachFilterAloneAndCombined() throws Exception {
    CatalogHttp.Shop httpShop = http.catalog().shop();
    OrderFixture.Shop shop = OrderFixture.shopFor(httpShop);
    UUID shopee = OrderFixture.ensureShopeeAccount(shop.tenantId(), "shopee-shop-1");
    Instant day = Instant.parse("2026-06-15T10:00:00Z");
    SalesOrder ready =
        insertAt(
            shop,
            "ORD-READY-F",
            "ACTIVE",
            "PAID",
            "READY_TO_PICK",
            "NONE",
            shop.channelAccountId(),
            day);
    insertAt(
        shop,
        "ORD-PICK",
        "ACTIVE",
        "PAID",
        "PICKING",
        "NONE",
        shop.channelAccountId(),
        day.plus(1, ChronoUnit.HOURS));
    insertAt(
        shop,
        "ORD-HOLD",
        "ACTIVE",
        "PAID",
        "UNFULFILLED",
        "SKU_NOT_MAPPED",
        shop.channelAccountId(),
        day.plus(2, ChronoUnit.HOURS));
    insertAt(
        shop,
        "ORD-SHOPEE",
        "ACTIVE",
        "COD_PENDING",
        "UNFULFILLED",
        "NONE",
        shopee,
        day.plus(3, ChronoUnit.HOURS));

    assertThat(http.get(OrderHttp.ordersPath("?fulfillment_status=READY_TO_PICK"), httpShop.owner())
            .body()
            .path("total")
            .asInt())
        .isEqualTo(1);
    assertThat(http.get(OrderHttp.ordersPath("?payment_status=COD_PENDING"), httpShop.owner())
            .body()
            .path("total")
            .asInt())
        .isEqualTo(1);
    assertThat(http.get(OrderHttp.ordersPath("?hold_reason=SKU_NOT_MAPPED"), httpShop.owner())
            .body()
            .path("total")
            .asInt())
        .isEqualTo(1);
    assertThat(http.get(OrderHttp.ordersPath("?channel=SHOPEE"), httpShop.owner()).body().path("total").asInt())
        .isEqualTo(1);
    assertThat(
            http.get(
                    OrderHttp.ordersPath(
                        "?fulfillment_status=READY_TO_PICK&payment_status=PAID&hold_reason=NONE"),
                    httpShop.owner())
                .body()
                .path("total")
                .asInt())
        .isEqualTo(1);
    assertThat(
            http.get(
                    OrderHttp.ordersPath(
                        "?ordered_from=2026-06-15&ordered_to=2026-06-16&fulfillment_status=READY_TO_PICK"),
                    httpShop.owner())
                .body()
                .path("items")
                .get(0)
                .path("id")
                .asString())
        .isEqualTo(ready.id().toString());
  }

  @Test
  void trackingSearchAndNonMatchingPhone() throws Exception {
    CatalogHttp.Shop httpShop = http.catalog().shop();
    OrderFixture.Shop shop = OrderFixture.shopFor(httpShop);
    SalesOrder order = fixture.insert(shop, "ORD-TRACK", "SHIPPED", "NONE");
    UUID shipmentId = UuidV7.generate();
    UUID warehouseId = UuidV7.generate();
    jdbc.update(
        "INSERT INTO warehouse (id, tenant_id, code, name, is_default) VALUES (?, ?, 'WH1', 'Main', true)",
        warehouseId,
        shop.tenantId());
    try (Connection admin = AuthTestSupport.admin();
        PreparedStatement ps =
            admin.prepareStatement(
                "INSERT INTO shipment (id, tenant_id, order_id, warehouse_id, tracking_no, carrier, status) "
                    + "VALUES (?, ?, ?, ?, ?, 'KERRY', 'IN_TRANSIT')")) {
      ps.setObject(1, shipmentId);
      ps.setObject(2, shop.tenantId());
      ps.setObject(3, order.id());
      ps.setObject(4, warehouseId);
      ps.setString(5, "TH999888777");
      ps.executeUpdate();
    }
    assertThat(http.get(OrderHttp.ordersPath("?q=TH999888777"), httpShop.owner()).body().path("total").asInt())
        .isEqualTo(1);
    assertThat(http.get(OrderHttp.ordersPath("?q=0819999999999"), httpShop.owner()).body().path("total").asInt())
        .isZero();
  }

  @Test
  void likeEscapeAndWildcard() throws Exception {
    CatalogHttp.Shop httpShop = http.catalog().shop();
    OrderFixture.Shop shop = OrderFixture.shopFor(httpShop);
    fixture.insert(shop, "ORD_LIKE_1", "READY_TO_PICK", "NONE");
    fixture.insert(shop, "ORDXLIKE1", "READY_TO_PICK", "NONE");
    assertThat(
            http.get(
                    OrderHttp.ordersPath(
                        "?q=" + URLEncoder.encode("ORD_LIKE_1", StandardCharsets.UTF_8)),
                    httpShop.owner())
                .body()
                .path("total")
                .asInt())
        .isEqualTo(1);
    assertThat(
            http.get(
                    OrderHttp.ordersPath("?q=" + URLEncoder.encode("%", StandardCharsets.UTF_8)),
                    httpShop.owner())
                .body()
                .path("total")
                .asInt())
        .isZero();
  }

  @Test
  void longDigitQueryFallsThroughToExternalId() throws Exception {
    CatalogHttp.Shop httpShop = http.catalog().shop();
    OrderFixture.Shop shop = OrderFixture.shopFor(httpShop);
    String external = "9123456789012345";
    fixture.insert(shop, external, "READY_TO_PICK", "NONE");
    assertThat(
            http.get(
                    OrderHttp.ordersPath(
                        "?q=" + URLEncoder.encode(external, StandardCharsets.UTF_8)),
                    httpShop.owner())
                .body()
                .path("total")
                .asInt())
        .isEqualTo(1);
  }

  private SalesOrder insertAt(
      OrderFixture.Shop shop,
      String externalId,
      String orderStatus,
      String paymentStatus,
      String fulfillment,
      String hold,
      UUID channelAccountId,
      Instant orderedAt)
      throws Exception {
    SalesOrder order =
        fixture.insert(
            shop, externalId, orderStatus, paymentStatus, fulfillment, hold, channelAccountId);
    try (Connection admin = AuthTestSupport.admin();
        PreparedStatement ps =
            admin.prepareStatement("UPDATE sales_order SET ordered_at = ? WHERE id = ?")) {
      ps.setObject(1, java.sql.Timestamp.from(orderedAt));
      ps.setObject(2, order.id());
      ps.executeUpdate();
    }
    return order;
  }
}
