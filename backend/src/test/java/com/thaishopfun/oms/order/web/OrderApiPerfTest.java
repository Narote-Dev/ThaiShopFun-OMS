package com.thaishopfun.oms.order.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.thaishopfun.oms.auth.AuthTestSupport;
import com.thaishopfun.oms.auth.UuidV7;
import com.thaishopfun.oms.catalog.CatalogHttp;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.UUID;
import com.thaishopfun.oms.pii.PiiCipher;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

/** Plan AC: 10k orders list/search under one second after warm-up. */
class OrderApiPerfTest extends OrderIntegrationTest {

  private static final Logger log = LoggerFactory.getLogger(OrderApiPerfTest.class);

  @Autowired PiiCipher cipher;

  @Test
  void listPagesUnderOneSecond() throws Exception {
    CatalogHttp.Shop shop = http.catalog().shop();
    OrderFixture.Shop seeded = OrderFixture.channelFor(shop);
    seedOrders(shop.tenantId(), seeded.channelAccountId(), 10_000, true, cipher);
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
    seedOrders(noiseTenant, noiseChannel, 50, false, cipher);

    assertUnder1s(shop.owner(), OrderHttp.ordersPath("?limit=50"), "warm-up");
    assertUnder1s(shop.owner(), OrderHttp.ordersPath("?q=081-000-0001"), "phone search");
    assertNoSeqScanOnSalesOrder(shop.tenantId());
    assertUnder1s(shop.owner(), OrderHttp.ordersPath("?limit=50"), "first page");
    assertUnder2s(
        shop.owner(),
        OrderHttp.ordersPath("?fulfillment_status=READY_TO_PICK&hold_reason=NONE&limit=50"),
        "filtered");
    String deep =
        http.get(OrderHttp.ordersPath("?limit=50"), shop.owner())
            .body()
            .path("next_cursor")
            .asString();
    for (int i = 0; i < 179 && deep != null && !deep.isBlank(); i++) {
      deep =
          http.get(
                  OrderHttp.ordersPath(
                      "?limit=50&cursor="
                          + java.net.URLEncoder.encode(
                              deep, java.nio.charset.StandardCharsets.UTF_8)),
                  shop.owner())
              .body()
              .path("next_cursor")
              .asString();
    }
    assertUnder1s(
        shop.owner(),
        OrderHttp.ordersPath(
            "?limit=50&cursor="
                + java.net.URLEncoder.encode(
                    deep == null ? "" : deep, java.nio.charset.StandardCharsets.UTF_8)),
        "deep page");
  }

  private void assertUnder2s(String token, String path, String label) {
    long start = System.nanoTime();
    CatalogHttp.Result result = http.get(path, token);
    long ms = (System.nanoTime() - start) / 1_000_000;
    log.info("{} {} ms status={}", label, ms, result.status());
    assertThat(result.status()).isEqualTo(200);
    assertThat(ms).isLessThan(2000);
  }

  private void assertUnder1s(String token, String path, String label) {
    long start = System.nanoTime();
    CatalogHttp.Result result = http.get(path, token);
    long ms = (System.nanoTime() - start) / 1_000_000;
    log.info("{} {} ms status={}", label, ms, result.status());
    assertThat(result.status()).isEqualTo(200);
    assertThat(ms).isLessThan(1000);
  }

  private static void seedOrders(
      UUID tenantId, UUID channelAccountId, int count, boolean varied, PiiCipher cipher)
      throws Exception {
    try (Connection admin = AuthTestSupport.admin()) {
      admin.setAutoCommit(false);
      try (PreparedStatement order =
          admin.prepareStatement(
              """
              INSERT INTO sales_order (
                id, tenant_id, channel_account_id, external_order_id, order_status, payment_status,
                fulfillment_status, hold_reason, payment_method, currency, subtotal, shipping_fee,
                discount, grand_total, ordered_at, version
              ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'PREPAID', 'THB', 100, 0, 0, 100, ?, 0)
              """)) {
        try (PreparedStatement recipient =
            admin.prepareStatement(
                """
                INSERT INTO order_recipient (
                  order_id, tenant_id, name_enc, phone_enc, phone_hash, phone_last4, address_enc,
                  province, postcode, pii_status
                ) VALUES (?, ?, NULL, NULL, ?, '0001', NULL, 'BKK', '10110', 'ACTIVE')
                """)) {
          Instant now = Instant.now();
          byte[] phoneHash = cipher.phoneHash("08100000001");
          for (int i = 0; i < count; i++) {
            UUID id = UuidV7.generate();
            String fulfillment = varied && i % 7 == 0 ? "SHIPPED" : "READY_TO_PICK";
            String hold = varied && i % 11 == 0 ? "SKU_NOT_MAPPED" : "NONE";
            order.setObject(1, id);
            order.setObject(2, tenantId);
            order.setObject(3, channelAccountId);
            order.setString(4, "PERF-" + i);
            order.setString(5, "ACTIVE");
            order.setString(6, i % 3 == 0 ? "UNPAID" : "PAID");
            order.setString(7, fulfillment);
            order.setString(8, hold);
            order.setObject(9, java.sql.Timestamp.from(now.minusSeconds(i)));
            order.addBatch();
            if (i < 20) {
              recipient.setObject(1, id);
              recipient.setObject(2, tenantId);
              recipient.setBytes(3, phoneHash);
              recipient.addBatch();
            }
            if (i % 500 == 0) {
              order.executeBatch();
              recipient.executeBatch();
            }
          }
          order.executeBatch();
          recipient.executeBatch();
        }
      }
      admin.commit();
    }
  }

  private void assertNoSeqScanOnSalesOrder(UUID tenantId) throws Exception {
    try (Connection admin = AuthTestSupport.admin();
        PreparedStatement ps =
            admin.prepareStatement(
                """
                EXPLAIN (FORMAT TEXT)
                SELECT o.id FROM sales_order o
                WHERE o.tenant_id = ?
                ORDER BY o.ordered_at DESC, o.id DESC
                LIMIT 50
                """)) {
      ps.setObject(1, tenantId);
      StringBuilder plan = new StringBuilder();
      try (var rs = ps.executeQuery()) {
        while (rs.next()) {
          plan.append(rs.getString(1)).append('\n');
        }
      }
      log.info("EXPLAIN plan:\n{}", plan);
      assertThat(plan.toString().toLowerCase()).doesNotContain("seq scan on sales_order");
    }
  }
}
