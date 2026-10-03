package com.thaishopfun.oms.order.web;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.thaishopfun.oms.catalog.CatalogHttp;
import com.thaishopfun.oms.order.OrderRecipientRepository;
import com.thaishopfun.oms.order.OrderStatusHistoryRepository;
import com.thaishopfun.oms.order.SalesOrder;
import com.thaishopfun.oms.order.SalesOrderRepository;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.JsonNode;

class OrderApiReadTest extends OrderIntegrationTest {

  @Autowired SalesOrderRepository orders;
  @Autowired OrderRecipientRepository recipients;
  @Autowired OrderStatusHistoryRepository history;
  @Autowired PlatformTransactionManager transactions;

  private OrderFixture fixture;
  private ListAppender<ILoggingEvent> logs;
  private Logger root;
  private Logger jdbc;
  private Level jdbcLevelBefore;

  @BeforeEach
  void setup() {
    fixture = new OrderFixture(orders, recipients, history, transactions);
    logs = new ListAppender<>();
    logs.start();
    root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    root.addAppender(logs);
    jdbc = (Logger) LoggerFactory.getLogger("org.springframework.jdbc");
    jdbcLevelBefore = jdbc.getLevel();
    jdbc.setLevel(Level.TRACE);
  }

  @AfterEach
  void teardownLogs() {
    root.detachAppender(logs);
    jdbc.setLevel(jdbcLevelBefore);
  }

  @Test
  void listDetailAndMaskedPhone() throws Exception {
    CatalogHttp.Shop httpShop = http.catalog().shop();
    OrderFixture.Shop shop = OrderFixture.shopFor(httpShop);
    SalesOrder ready = fixture.insert(shop, "ORD-READY", "READY_TO_PICK", "NONE");
    CatalogHttp.Result list =
        http.get(OrderHttp.ordersPath("?fulfillment_status=READY_TO_PICK"), httpShop.owner());
    assertThat(list.status()).isEqualTo(200);
    assertThat(list.body().path("items")).hasSize(1);
    assertThat(list.body().path("items").get(0).path("phone_masked").asString())
        .isEqualTo("***-***-5678");

    CatalogHttp.Result detail = http.get(OrderHttp.ordersPath("/" + ready.id()), httpShop.owner());
    assertThat(detail.status()).isEqualTo(200);
    assertThat(detail.raw()).doesNotContain(OrderFixture.NAME).doesNotContain(OrderFixture.PHONE);
    assertThat(detail.body().path("recipient").path("phone_masked").asString())
        .isEqualTo("***-***-5678");
    assertThat(detail.body().path("timeline")).isNotEmpty();

    CatalogHttp.Result byExternal =
        http.get(OrderHttp.ordersPath("?q=ORD-READY"), httpShop.owner());
    assertThat(byExternal.status()).isEqualTo(200);
    assertThat(byExternal.body().path("total").asInt()).isEqualTo(1);

    CatalogHttp.Result staff =
        http.get(OrderHttp.ordersPath("/" + ready.id()), http.catalog().member(httpShop, "STAFF"));
    assertThat(staff.body().path("recipient").path("phone_masked").asString())
        .isEqualTo("***-***-5678");

    for (ILoggingEvent event : logs.list) {
      assertThat(event.getFormattedMessage()).doesNotContain(OrderFixture.NAME);
      assertThat(event.getFormattedMessage()).doesNotContain(OrderFixture.PHONE);
      assertThat(event.getFormattedMessage()).doesNotContain(OrderFixture.ADDRESS);
    }
  }

  @Test
  void redactedRecipientOnDetail() throws Exception {
    CatalogHttp.Shop httpShop = http.catalog().shop();
    OrderFixture.Shop shop = OrderFixture.shopFor(httpShop);
    SalesOrder order = fixture.insert(shop, "ORD-REDACT", "READY_TO_PICK", "NONE");
    fixture.inTenant(
        shop.tenantId(),
        () -> {
          recipients.redact(order.id());
          return null;
        });
    CatalogHttp.Result detail = http.get(OrderHttp.ordersPath("/" + order.id()), httpShop.owner());
    assertThat(detail.body().path("recipient").path("name_masked").asString())
        .isEqualTo("redacted");
    assertThat(detail.body().path("recipient").path("phone_masked").asString())
        .isEqualTo("redacted");
    assertThat(detail.body().path("recipient").path("pii_status").asString()).isEqualTo("REDACTED");
  }

  @Test
  void phoneSearchDoesNotLeakPiiInLogs() throws Exception {
    CatalogHttp.Shop httpShop = http.catalog().shop();
    OrderFixture.Shop shop = OrderFixture.shopFor(httpShop);
    fixture.insert(shop, "ORD-LOG", "READY_TO_PICK", "NONE");
    http.get(OrderHttp.ordersPath("?q=081-234-5678"), httpShop.owner());
    for (ILoggingEvent event : logs.list) {
      assertThat(event.getFormattedMessage()).doesNotContain(OrderFixture.NAME);
      assertThat(event.getFormattedMessage()).doesNotContain(OrderFixture.PHONE);
      assertThat(event.getFormattedMessage()).doesNotContain(OrderFixture.ADDRESS);
    }
  }

  @Test
  void phoneSearchFindsOrder() throws Exception {
    CatalogHttp.Shop httpShop = http.catalog().shop();
    OrderFixture.Shop shop = OrderFixture.shopFor(httpShop);
    fixture.insert(shop, "ORD-PHONE", "UNFULFILLED", "NONE");
    CatalogHttp.Result found = http.get(OrderHttp.ordersPath("?q=081-234-5678"), httpShop.owner());
    assertThat(found.status()).isEqualTo(200);
    assertThat(found.body().path("total").asInt()).isEqualTo(1);
    CatalogHttp.Result plus = http.get(OrderHttp.ordersPath("?q=%2B66812345678"), httpShop.owner());
    assertThat(plus.body().path("total").asInt()).isEqualTo(1);
  }

  @Test
  void otherTenantGets404() throws Exception {
    CatalogHttp.Shop a = http.catalog().shop();
    OrderFixture.Shop shop = fixture.shopFor(a);
    SalesOrder order = fixture.insert(shop, "ORD-X", "UNFULFILLED", "NONE");
    CatalogHttp.Shop b = http.catalog().shop();
    CatalogHttp.Result detail = http.get(OrderHttp.ordersPath("/" + order.id()), b.owner());
    assertThat(detail.status()).isEqualTo(404);
    CatalogHttp.Result random = http.get(OrderHttp.ordersPath("/" + UUID.randomUUID()), a.owner());
    assertThat(random.status()).isEqualTo(404);
  }

  @Test
  void holdsEndpointGroups() throws Exception {
    CatalogHttp.Shop httpShop = http.catalog().shop();
    OrderFixture.Shop shop = OrderFixture.shopFor(httpShop);
    fixture.insert(shop, "ORD-HOLD", "UNFULFILLED", "SKU_NOT_MAPPED");
    CatalogHttp.Result holds = http.get(OrderHttp.ordersPath("/holds"), httpShop.owner());
    assertThat(holds.status()).isEqualTo(200);
    JsonNode groups = holds.body().path("groups");
    assertThat(groups)
        .anySatisfy(g -> assertThat(g.path("hold_reason").asString()).isEqualTo("SKU_NOT_MAPPED"));
  }
}
