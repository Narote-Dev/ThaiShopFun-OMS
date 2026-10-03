package com.thaishopfun.oms.order.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.thaishopfun.oms.auth.AuthTestSupport;
import com.thaishopfun.oms.catalog.CatalogHttp;
import com.thaishopfun.oms.order.OrderRecipientRepository;
import com.thaishopfun.oms.order.OrderStatusHistoryRepository;
import com.thaishopfun.oms.order.SalesOrder;
import com.thaishopfun.oms.order.SalesOrderRepository;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;

class OrderApiCursorStabilityTest extends OrderIntegrationTest {

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
  void insertsBetweenPagesDoNotDuplicateOrGap() throws Exception {
    CatalogHttp.Shop httpShop = http.catalog().shop();
    OrderFixture.Shop shop = OrderFixture.shopFor(httpShop);
    Instant base = Instant.parse("2026-07-01T12:00:00Z");
    for (int i = 0; i < 5; i++) {
      stamp(
          fixture.insert(shop, "CUR-" + i, "READY_TO_PICK", "NONE"),
          base.plus(i, ChronoUnit.MINUTES));
    }
    CatalogHttp.Result page1 =
        http.get(OrderHttp.ordersPath("?limit=2&ordered_to=2026-07-02"), httpShop.owner());
    assertThat(page1.status()).isEqualTo(200);
    String cursor = page1.body().path("next_cursor").asString();
    assertThat(cursor).isNotBlank();
    stamp(
        fixture.insert(shop, "CUR-NEW", "READY_TO_PICK", "NONE"), base.plus(2, ChronoUnit.MINUTES));
    CatalogHttp.Result page2 =
        http.get(
            OrderHttp.ordersPath(
                "?limit=2&ordered_to=2026-07-02&cursor="
                    + URLEncoder.encode(cursor, StandardCharsets.UTF_8)),
            httpShop.owner());
    CatalogHttp.Result page3 =
        http.get(
            OrderHttp.ordersPath(
                "?limit=2&ordered_to=2026-07-02&cursor="
                    + URLEncoder.encode(
                        page2.body().path("next_cursor").asString(), StandardCharsets.UTF_8)),
            httpShop.owner());
    Set<String> ids = new HashSet<>();
    for (CatalogHttp.Result page : Arrays.asList(page1, page2, page3)) {
      for (var item : page.body().path("items")) {
        ids.add(item.path("id").asString());
      }
    }
    assertThat(ids).hasSize(5);
    assertThat(ids).doesNotContain((String) null);
  }

  private static void stamp(SalesOrder order, Instant orderedAt) throws Exception {
    try (Connection admin = AuthTestSupport.admin();
        PreparedStatement ps =
            admin.prepareStatement("UPDATE sales_order SET ordered_at = ? WHERE id = ?")) {
      ps.setObject(1, java.sql.Timestamp.from(orderedAt));
      ps.setObject(2, order.id());
      ps.executeUpdate();
    }
  }
}
