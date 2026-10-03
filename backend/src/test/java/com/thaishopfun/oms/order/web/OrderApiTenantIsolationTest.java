package com.thaishopfun.oms.order.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.thaishopfun.oms.catalog.CatalogHttp;
import com.thaishopfun.oms.order.OrderRecipientRepository;
import com.thaishopfun.oms.order.OrderStatusHistoryRepository;
import com.thaishopfun.oms.order.SalesOrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;

class OrderApiTenantIsolationTest extends OrderIntegrationTest {

  @Autowired SalesOrderRepository orders;
  @Autowired OrderRecipientRepository recipients;
  @Autowired OrderStatusHistoryRepository history;
  @Autowired PlatformTransactionManager transactions;

  private OrderFixture fixture;

  @BeforeEach
  void setup() {
    fixture = new OrderFixture(orders, recipients, history, transactions);
  }

  @Test
  void listSearchAndHoldsAreTenantScoped() throws Exception {
    CatalogHttp.Shop a = http.catalog().shop();
    CatalogHttp.Shop b = http.catalog().shop();
    OrderFixture.Shop shopA = OrderFixture.shopFor(a);
    OrderFixture.Shop shopB = OrderFixture.shopFor(b);
    fixture.insert(shopA, "SHARED-EXT", "READY_TO_PICK", "NONE");
    fixture.insert(shopB, "SHARED-EXT", "READY_TO_PICK", "NONE");
    fixture.insert(shopA, "ORD-A-HOLD", "UNFULFILLED", "SKU_NOT_MAPPED");
    fixture.insert(shopB, "ORD-B-HOLD", "UNFULFILLED", "SKU_NOT_MAPPED");

    assertThat(
            http.get(OrderHttp.ordersPath("?q=SHARED-EXT"), a.owner()).body().path("total").asInt())
        .isEqualTo(1);
    assertThat(
            http.get(OrderHttp.ordersPath("?q=SHARED-EXT"), b.owner()).body().path("total").asInt())
        .isEqualTo(1);
    assertThat(http.get(OrderHttp.ordersPath("?q=081-234-5678"), a.owner()).body().path("total").asInt())
        .isEqualTo(2);
    assertThat(http.get(OrderHttp.ordersPath("?q=081-234-5678"), b.owner()).body().path("total").asInt())
        .isEqualTo(2);

    int holdsA =
        http.get(OrderHttp.ordersPath("/holds"), a.owner()).body().path("groups").size();
    int holdsB =
        http.get(OrderHttp.ordersPath("/holds"), b.owner()).body().path("groups").size();
    assertThat(holdsA).isEqualTo(1);
    assertThat(holdsB).isEqualTo(1);
  }
}
