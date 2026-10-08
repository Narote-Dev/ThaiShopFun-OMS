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
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@SkipInvariantCheck("Deliberate invariant corruption")
class InvariantCheckerBreakTest extends StockTestBase {

  @Autowired InvariantChecker checker;

  @Test
  @Disabled("inventory_quantity_check prevents reserved > on_hand; DB enforces this invariant")
  void reservedAboveOnHand() {
    StockFixture.Shop shop = fixture.shop("ACTIVE");
    fixture.sku(shop, 5);
    assertCode(shop.tenant(), InvariantCodes.STOCK_RESERVED_BOUNDS);
  }

  @Test
  void orphanLedgerDelta() {
    StockFixture.Shop shop = fixture.shop("ACTIVE");
    UUID sku = fixture.sku(shop, 3);
    fixture.inTenant(
        shop.tenant(),
        () -> {
          long seq =
              jdbc.queryForObject(
                  """
                  UPDATE inventory SET ledger_seq = ledger_seq + 1
                  WHERE tenant_id = ? AND sku_id = ? AND warehouse_id = ?
                  RETURNING ledger_seq
                  """,
                  Long.class,
                  shop.tenant(),
                  sku,
                  shop.warehouse());
          jdbc.update(
              """
              INSERT INTO inventory_ledger (
                id, tenant_id, sku_id, warehouse_id, delta_on_hand, delta_reserved,
                reason, actor, ledger_seq
              ) VALUES (?, ?, ?, ?, 1, 0, 'ADJUST_IN', 'break-test', ?)
              """,
              UuidV7.generate(),
              shop.tenant(),
              sku,
              shop.warehouse(),
              seq);
        });
    assertStockCode(shop.tenant(), InvariantCodes.STOCK_LEDGER_MISMATCH);
  }

  @Test
  void activeReservationMismatch() {
    StockFixture.Shop shop = fixture.shop("ACTIVE");
    UUID sku = fixture.sku(shop, 10);
    fixture.inTenant(
        shop.tenant(),
        () ->
            jdbc.update(
                """
                UPDATE inventory SET reserved = 3
                WHERE tenant_id = ? AND sku_id = ? AND warehouse_id = ?
                """,
                shop.tenant(),
                sku,
                shop.warehouse()));
    assertStockCode(shop.tenant(), InvariantCodes.STOCK_ACTIVE_RESERVATION_MISMATCH);
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
    fixture.inTenant(
        shop.tenant(),
        () -> {
          jdbc.update(
              """
              INSERT INTO sales_order (
                id, tenant_id, channel_account_id, external_order_id, order_status, payment_status,
                fulfillment_status, hold_reason, payment_method, currency, subtotal, shipping_fee,
                discount, grand_total, ordered_at, version
              ) VALUES (?, ?, ?, 'brk-cancel', 'CANCELLED', 'PAID', 'UNFULFILLED', 'NONE', 'PREPAID',
                'THB', 1, 0, 0, 1, CURRENT_TIMESTAMP, 0)
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
        });
    assertCode(shop.tenant(), InvariantCodes.ORDER_CANCELLED_ACTIVE_RESERVATION);
  }

  private void assertStockCode(UUID tenantId, String code) {
    List<Violation> violations = checker.checkTenantStock(tenantId);
    assertThat(violations).anyMatch(v -> code.equals(v.code()));
  }

  private void assertCode(UUID tenantId, String code) {
    List<Violation> violations = checker.checkTenant(tenantId);
    assertThat(violations).anyMatch(v -> code.equals(v.code()));
  }
}
