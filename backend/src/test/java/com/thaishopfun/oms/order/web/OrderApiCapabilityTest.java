package com.thaishopfun.oms.order.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.thaishopfun.oms.catalog.CatalogHttp;
import com.thaishopfun.oms.order.OrderRecipientRepository;
import com.thaishopfun.oms.order.OrderStatusHistoryRepository;
import com.thaishopfun.oms.order.SalesOrder;
import com.thaishopfun.oms.order.SalesOrderRepository;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;

class OrderApiCapabilityTest extends OrderIntegrationTest {

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
  void shopeeDetailAndCancelUnsupported() throws Exception {
    CatalogHttp.Shop httpShop = http.catalog().shop();
    OrderFixture.Shop shop = OrderFixture.shopFor(httpShop);
    UUID shopee = OrderFixture.ensureShopeeAccount(shop.tenantId(), "shopee-cap");
    SalesOrder order =
        fixture.insert(shop, "ORD-SHOPEE-CAP", "ACTIVE", "PAID", "READY_TO_PICK", "NONE", shopee);
    CatalogHttp.Result detail = http.get(OrderHttp.ordersPath("/" + order.id()), httpShop.owner());
    assertThat(detail.status()).isEqualTo(200);
    assertThat(detail.body().path("supports_cancel_request").asBoolean()).isFalse();
    CatalogHttp.Result cancel =
        http.post(
            OrderHttp.ordersPath("/" + order.id() + "/cancel-requests"),
            httpShop.owner(),
            Map.of("reason", "nope"));
    assertThat(cancel.status()).isEqualTo(422);
    assertThat(cancel.error()).isEqualTo("CAPABILITY_UNSUPPORTED");
  }
}
