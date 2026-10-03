package com.thaishopfun.oms.order.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.thaishopfun.oms.catalog.CatalogHttp;
import com.thaishopfun.oms.order.OrderRecipientRepository;
import com.thaishopfun.oms.order.OrderStatusHistoryRepository;
import com.thaishopfun.oms.order.SalesOrder;
import com.thaishopfun.oms.order.SalesOrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

class OrderHoldRecheckApiTest extends OrderIntegrationTest {

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
  void rejectIdempotencyKeyLongerThan128BeforeClaim() throws Exception {
    CatalogHttp.Shop httpShop = http.catalog().shop();
    OrderFixture.Shop shop = OrderFixture.shopFor(httpShop);
    SalesOrder order = fixture.insert(shop, "H-RECHECK-LEN", "UNFULFILLED", "OUT_OF_STOCK");
    String key129 = "k".repeat(129);
    CatalogHttp.Result response =
        http.postHoldRecheck(order.id().toString(), httpShop.owner(), key129);
    assertThat(response.status()).isEqualTo(422);
    assertThat(response.body().path("error").asString()).isEqualTo("VALIDATION_FAILED");
    assertThat(response.body().path("errors").get(0).path("field").asString())
        .isEqualTo("Idempotency-Key");
    String storageKey = order.id() + ":" + key129;
    long rows =
        fixture.inTenant(
            shop.tenantId(),
            () ->
                jdbc.queryForObject(
                    """
                    SELECT count(*) FROM idempotency_key
                    WHERE scope = ? AND "key" = ?
                    """,
                    Long.class,
                    OrderHoldRecheckIdempotency.SCOPE,
                    storageKey));
    assertThat(rows).isZero();
  }

  @Test
  void acceptIdempotencyKeyAt128Characters() throws Exception {
    CatalogHttp.Shop httpShop = http.catalog().shop();
    OrderFixture.Shop shop = OrderFixture.shopFor(httpShop);
    SalesOrder order = fixture.insert(shop, "H-RECHECK-128", "UNFULFILLED", "OUT_OF_STOCK");
    String key128 = "k".repeat(128);
    CatalogHttp.Result response =
        http.postHoldRecheck(order.id().toString(), httpShop.owner(), key128);
    assertThat(response.status()).isEqualTo(200);
  }
}
