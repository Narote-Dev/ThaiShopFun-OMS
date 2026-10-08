package com.thaishopfun.oms.stock;

import static org.assertj.core.api.Assertions.assertThat;

import com.thaishopfun.oms.auth.AuthTestSupport;
import com.thaishopfun.oms.auth.UuidV7;
import com.thaishopfun.oms.invariant.InvariantChecker;
import com.thaishopfun.oms.invariant.InvariantCodes;
import com.thaishopfun.oms.invariant.SkipInvariantCheck;
import com.thaishopfun.oms.invariant.Violation;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@SkipInvariantCheck("Deliberate invariant corruption")
class InvariantCheckerBreakTest extends StockTestBase {

  @Autowired InvariantChecker checker;

  @Test
  void reservedAboveOnHand() throws Exception {
    StockFixture.Shop shop = fixture.shop("ACTIVE");
    UUID sku = fixture.sku(shop, 5);
    try (Connection admin = AuthTestSupport.admin();
        Statement st = admin.createStatement()) {
      st.execute("SET session_replication_role = replica");
      st.executeUpdate(
          "UPDATE inventory SET reserved = on_hand + 1 WHERE tenant_id = '"
              + shop.tenant()
              + "' AND sku_id = '"
              + sku
              + "'");
      st.execute("SET session_replication_role = DEFAULT");
    }
    assertCode(shop.tenant(), InvariantCodes.STOCK_RESERVED_BOUNDS);
  }

  @Test
  void orphanLedgerDelta() {
    StockFixture.Shop shop = fixture.shop("ACTIVE");
    UUID sku = fixture.sku(shop, 3);
    as(
        shop,
        () -> {
          jdbc.update(
              """
              UPDATE inventory SET on_hand = on_hand + 1, stock_version = stock_version + 1
              WHERE tenant_id = ? AND sku_id = ? AND warehouse_id = ?
              """,
              shop.tenant(),
              sku,
              shop.warehouse());
          return null;
        });
    assertCode(shop.tenant(), InvariantCodes.STOCK_LEDGER_MISMATCH);
  }

  @Test
  void activeReservationMismatch() {
    StockFixture.Shop shop = fixture.shop("ACTIVE");
    UUID sku = fixture.sku(shop, 10);
    UUID reservation = UuidV7.generate();
    as(
        shop,
        () -> {
          jdbc.update(
              """
              INSERT INTO stock_reservation (
                id, tenant_id, sku_id, warehouse_id, owner_type, owner_ref, status, qty,
                reservation_group_id, expires_at
              ) VALUES (?, ?, ?, ?, 'CHECKOUT', ?, 'ACTIVE', 2, ?, now() + interval '1 hour')
              """,
              reservation,
              shop.tenant(),
              sku,
              shop.warehouse(),
              "chk-break",
              reservation);
          return null;
        });
    assertCode(shop.tenant(), InvariantCodes.STOCK_ACTIVE_RESERVATION_MISMATCH);
  }

  @Test
  void tableWithoutForceRls() throws Exception {
    try (Connection admin = AuthTestSupport.admin();
        Statement st = admin.createStatement()) {
      st.execute(
          """
          CREATE TABLE IF NOT EXISTS invariant_break_tenant_probe (
            id uuid PRIMARY KEY,
            tenant_id uuid NOT NULL
          )
          """);
    }
    assertThat(checker.checkSchema())
        .anyMatch(v -> InvariantCodes.SCHEMA_FORCE_RLS.equals(v.code()));
    try (Connection admin = AuthTestSupport.admin();
        Statement st = admin.createStatement()) {
      st.execute("DROP TABLE IF EXISTS invariant_break_tenant_probe");
    }
  }

  @Test
  void cancelledOrderKeepsActiveReservation() {
    StockFixture.Shop shop = fixture.shop("ACTIVE");
    UUID sku = fixture.sku(shop, 5);
    UUID orderId = UuidV7.generate();
    UUID channel = fixture.channelAccount(shop, "ACTIVE", "CONNECTED");
    as(
        shop,
        () -> {
          jdbc.update(
              """
              INSERT INTO sales_order (
                id, tenant_id, channel_account_id, external_order_id, order_status, payment_status,
                fulfillment_status, hold_reason, payment_method, currency, subtotal, shipping_fee,
                discount, grand_total, ordered_at, version
              ) VALUES (?, ?, ?, 'brk-cancel', 'CANCELLED', 'PAID', 'UNFULFILLED', 'NONE', 'PREPAID',
                'THB', 1, 0, 0, 1, now(), 0)
              """,
              orderId,
              shop.tenant(),
              channel);
          UUID reservation = UuidV7.generate();
          jdbc.update(
              """
              INSERT INTO stock_reservation (
                id, tenant_id, sku_id, warehouse_id, owner_type, owner_ref, status, qty,
                reservation_group_id, expires_at
              ) VALUES (?, ?, ?, ?, 'ORDER', ?, 'ACTIVE', 1, ?, NULL)
              """,
              reservation,
              shop.tenant(),
              sku,
              shop.warehouse(),
              orderId.toString(),
              reservation);
          return null;
        });
    assertCode(shop.tenant(), InvariantCodes.ORDER_CANCELLED_ACTIVE_RESERVATION);
  }

  private void assertCode(UUID tenantId, String code) {
    List<Violation> violations = checker.checkTenant(tenantId);
    assertThat(violations).anyMatch(v -> code.equals(v.code()));
  }
}
