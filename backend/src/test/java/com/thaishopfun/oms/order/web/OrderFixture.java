package com.thaishopfun.oms.order.web;

import com.thaishopfun.oms.auth.AuthTestSupport;
import com.thaishopfun.oms.auth.UuidV7;
import com.thaishopfun.oms.order.OrderRecipientRepository;
import com.thaishopfun.oms.order.OrderStatusHistoryRepository;
import com.thaishopfun.oms.order.Recipient;
import com.thaishopfun.oms.order.SalesOrder;
import com.thaishopfun.oms.order.SalesOrderRepository;
import com.thaishopfun.oms.tenant.TenantContext;
import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Seeds orders for T17 API tests. */
final class OrderFixture {

  static final String NAME = "สมชาย ใจดี";
  static final String PHONE = "081-234-5678";

  private final SalesOrderRepository orders;
  private final OrderRecipientRepository recipients;
  private final OrderStatusHistoryRepository history;
  private final TransactionTemplate tx;

  OrderFixture(
      SalesOrderRepository orders,
      OrderRecipientRepository recipients,
      OrderStatusHistoryRepository history,
      PlatformTransactionManager transactions) {
    this.orders = orders;
    this.recipients = recipients;
    this.history = history;
    this.tx = new TransactionTemplate(transactions);
  }

  record Shop(UUID tenantId, UUID channelAccountId, String tsfShopId) {}

  static Shop channelFor(com.thaishopfun.oms.catalog.CatalogHttp.Shop httpShop)
      throws SQLException {
    return shopFor(httpShop);
  }

  static Shop shopFor(com.thaishopfun.oms.catalog.CatalogHttp.Shop httpShop) throws SQLException {
    try (java.sql.Connection admin = AuthTestSupport.admin()) {
      exec(
          admin,
          "UPDATE tenant SET tsf_shop_id = ? WHERE id = ?",
          httpShop.shopId(),
          httpShop.tenantId());
      UUID resolved = findChannel(admin, httpShop.tenantId(), httpShop.shopId());
      if (resolved == null) {
        resolved = UuidV7.generate();
        exec(
            admin,
            """
            INSERT INTO channel_account (id, tenant_id, channel, external_shop_id, status, mode)
            VALUES (?, ?, 'TSF', ?, 'CONNECTED', 'ACTIVE')
            """,
            resolved,
            httpShop.tenantId(),
            httpShop.shopId());
      }
      return new Shop(httpShop.tenantId(), resolved, httpShop.shopId());
    }
  }

  private static UUID findChannel(java.sql.Connection admin, UUID tenantId, String shopId)
      throws SQLException {
    try (PreparedStatement statement =
        admin.prepareStatement(
            "SELECT id FROM channel_account WHERE tenant_id = ? AND external_shop_id = ?")) {
      statement.setObject(1, tenantId);
      statement.setString(2, shopId);
      try (java.sql.ResultSet rows = statement.executeQuery()) {
        return rows.next() ? rows.getObject("id", UUID.class) : null;
      }
    }
  }

  private static void exec(java.sql.Connection connection, String sql, Object... params)
      throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      for (int i = 0; i < params.length; i++) {
        statement.setObject(i + 1, params[i]);
      }
      statement.executeUpdate();
    }
  }

  SalesOrder insert(Shop shop, String externalId, String fulfillment, String hold) {
    return inTenant(
        shop.tenantId(),
        () -> {
          SalesOrder order =
              new SalesOrder(
                  UuidV7.generate(),
                  shop.tenantId(),
                  shop.channelAccountId(),
                  externalId,
                  "ACTIVE",
                  "PAID",
                  fulfillment,
                  hold,
                  null,
                  null,
                  "PREPAID",
                  "THB",
                  new BigDecimal("100"),
                  BigDecimal.ZERO,
                  BigDecimal.ZERO,
                  new BigDecimal("100"),
                  Instant.now().truncatedTo(ChronoUnit.MICROS),
                  Instant.now().truncatedTo(ChronoUnit.MICROS),
                  null,
                  1L,
                  0);
          orders.insert(order);
          recipients.insert(
              order.id(),
              new Recipient(NAME, PHONE, "{\"line1\":\"x\"}", "Bangkok", "10110"),
              null);
          history.append(order.id(), "FULFILLMENT", "UNFULFILLED", fulfillment, "test", "SYSTEM");
          return order;
        });
  }

  <T> T inTenant(UUID tenantId, java.util.function.Supplier<T> work) {
    TenantContext.set(tenantId, null);
    try {
      return tx.execute(status -> work.get());
    } finally {
      TenantContext.clear();
    }
  }
}
