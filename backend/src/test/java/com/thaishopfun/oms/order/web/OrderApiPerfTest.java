package com.thaishopfun.oms.order.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.thaishopfun.oms.auth.AuthTestSupport;
import com.thaishopfun.oms.auth.UuidV7;
import com.thaishopfun.oms.catalog.CatalogHttp;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Plan AC: 10k orders list/search under one second after warm-up. */
class OrderApiPerfTest extends OrderIntegrationTest {

  private static final Logger log = LoggerFactory.getLogger(OrderApiPerfTest.class);

  @Test
  void listPagesUnderOneSecond() throws Exception {
    CatalogHttp.Shop shop = http.catalog().shop();
    OrderFixture.Shop seeded = OrderFixture.channelFor(shop);
    seedOrders(shop.tenantId(), seeded.channelAccountId(), 10_000);
    UUID noiseTenant = UuidV7.generate();
    UUID noiseChannel = UuidV7.generate();
    try (Connection admin = AuthTestSupport.admin()) {
      try (PreparedStatement statement =
          admin.prepareStatement(
              "INSERT INTO tenant (id, name, tsf_shop_id, membership_tier, entitlement_status, ent_ver) "
                  + "VALUES (?, 'Noise', ?, 'PRO', 'ACTIVE', 1)")) {
        statement.setObject(1, noiseTenant);
        statement.setString(2, "noise-" + noiseTenant);
        statement.executeUpdate();
      }
      try (PreparedStatement statement =
          admin.prepareStatement(
              """
              INSERT INTO channel_account (id, tenant_id, channel, external_shop_id, status, mode)
              VALUES (?, ?, 'TSF', ?, 'CONNECTED', 'ACTIVE')
              """)) {
        statement.setObject(1, noiseChannel);
        statement.setObject(2, noiseTenant);
        statement.setString(3, "noise-" + noiseTenant);
        statement.executeUpdate();
      }
    }
    seedOrders(noiseTenant, noiseChannel, 50);

    assertUnder1s(shop.owner(), OrderHttp.ordersPath("?limit=50"), "warm-up");
    assertUnder1s(shop.owner(), OrderHttp.ordersPath("?limit=50"), "first page");
    assertUnder1s(
        shop.owner(),
        OrderHttp.ordersPath("?fulfillment_status=READY_TO_PICK&hold_reason=NONE&limit=50"),
        "filtered");
    assertUnder1s(shop.owner(), OrderHttp.ordersPath("?limit=50&offset=9000"), "deep page");
  }

  private void assertUnder1s(String token, String path, String label) {
    long start = System.nanoTime();
    CatalogHttp.Result result = http.get(path, token);
    long ms = (System.nanoTime() - start) / 1_000_000;
    log.info("{} {} ms status={}", label, ms, result.status());
    assertThat(result.status()).isEqualTo(200);
    assertThat(ms).isLessThan(1000);
  }

  private static void seedOrders(UUID tenantId, UUID channelAccountId, int count) throws Exception {
    try (Connection admin = AuthTestSupport.admin()) {
      admin.setAutoCommit(false);
      try (PreparedStatement order =
          admin.prepareStatement(
              """
              INSERT INTO sales_order (
                id, tenant_id, channel_account_id, external_order_id, order_status, payment_status,
                fulfillment_status, hold_reason, payment_method, currency, subtotal, shipping_fee,
                discount, grand_total, ordered_at, version
              ) VALUES (?, ?, ?, ?, 'ACTIVE', 'PAID', 'READY_TO_PICK', 'NONE', 'PREPAID', 'THB',
                100, 0, 0, 100, ?, 0)
              """)) {
        Instant now = Instant.now();
        for (int i = 0; i < count; i++) {
          order.setObject(1, UuidV7.generate());
          order.setObject(2, tenantId);
          order.setObject(3, channelAccountId);
          order.setString(4, "PERF-" + i);
          order.setObject(5, java.sql.Timestamp.from(now));
          order.addBatch();
          if (i % 500 == 0) {
            order.executeBatch();
          }
        }
        order.executeBatch();
      }
      admin.commit();
    }
  }
}
