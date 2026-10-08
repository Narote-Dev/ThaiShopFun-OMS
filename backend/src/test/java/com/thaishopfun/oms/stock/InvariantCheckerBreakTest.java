package com.thaishopfun.oms.stock;

import static org.assertj.core.api.Assertions.assertThat;

import com.thaishopfun.oms.auth.AuthTestSupport;
import com.thaishopfun.oms.auth.UuidV7;
import com.thaishopfun.oms.invariant.InvariantChecker;
import com.thaishopfun.oms.invariant.InvariantCodes;
import com.thaishopfun.oms.invariant.SkipInvariantCheck;
import com.thaishopfun.oms.invariant.Violation;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@SkipInvariantCheck("Deliberate invariant corruption")
class InvariantCheckerBreakTest extends StockTestBase {

  private static final String INVENTORY_QUANTITY_CHECK =
      """
      ALTER TABLE inventory ADD CONSTRAINT inventory_quantity_check CHECK (
        on_hand >= 0 AND reserved >= 0 AND reserved <= on_hand
      )
      """;

  @Autowired InvariantChecker checker;

  @Test
  void reservedAboveOnHand() throws Exception {
    StockFixture.Shop shop = fixture.shop("ACTIVE");
    UUID sku = fixture.sku(shop, 5);
    try (Connection admin = AuthTestSupport.admin()) {
      admin.setAutoCommit(false);
      try (Statement st = admin.createStatement()) {
        st.execute("ALTER TABLE inventory DROP CONSTRAINT IF EXISTS inventory_quantity_check");
      }
      try (PreparedStatement ps =
          admin.prepareStatement(
              """
              UPDATE inventory SET reserved = 6, on_hand = 5
              WHERE tenant_id = ? AND sku_id = ? AND warehouse_id = ?
              """)) {
        ps.setObject(1, shop.tenant());
        ps.setObject(2, sku);
        ps.setObject(3, shop.warehouse());
        ps.executeUpdate();
      }
      admin.commit();
    }
    try {
      assertStockCode(shop.tenant(), InvariantCodes.STOCK_RESERVED_BOUNDS);
    } finally {
      try {
        fixture.inTenant(
            shop.tenant(),
            () ->
                jdbc.update(
                    """
                    UPDATE inventory SET reserved = 0, on_hand = 5
                    WHERE tenant_id = ? AND sku_id = ? AND warehouse_id = ?
                    """,
                    shop.tenant(),
                    sku,
                    shop.warehouse()));
      } finally {
        try (Connection admin = AuthTestSupport.admin();
            Statement st = admin.createStatement()) {
          st.execute(INVENTORY_QUANTITY_CHECK);
        }
      }
    }
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
  void reservationOwnerSplit() {
    StockFixture.Shop shop = fixture.shop("ACTIVE");
    UUID sku = fixture.sku(shop, 4);
    UUID group = UuidV7.generate();
    fixture.inTenant(
        shop.tenant(),
        () -> {
          UUID r1 = UuidV7.generate();
          UUID r2 = UuidV7.generate();
          jdbc.update(
              """
              INSERT INTO stock_reservation (
                id, tenant_id, sku_id, warehouse_id, owner_type, owner_ref, status, qty,
                reservation_group_id, expires_at
              ) VALUES (?, ?, ?, ?, 'ORDER', 'owner-a', 'ACTIVE', 1, ?, NULL)
              """,
              r1,
              shop.tenant(),
              sku,
              shop.warehouse(),
              group);
          jdbc.update(
              """
              INSERT INTO stock_reservation (
                id, tenant_id, sku_id, warehouse_id, owner_type, owner_ref, status, qty,
                reservation_group_id, expires_at
              ) VALUES (?, ?, ?, ?, 'ORDER', 'owner-b', 'ACTIVE', 1, ?, NULL)
              """,
              r2,
              shop.tenant(),
              sku,
              shop.warehouse(),
              group);
        });
    assertStockCode(shop.tenant(), InvariantCodes.STOCK_RESERVATION_OWNER_SPLIT);
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
  void orderReservationOrphan() {
    StockFixture.Shop shop = fixture.shop("ACTIVE");
    UUID sku = fixture.sku(shop, 2);
    UUID missingOrder = UuidV7.generate();
    fixture.inTenant(
        shop.tenant(),
        () -> {
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
              missingOrder.toString().toUpperCase(),
              reservation);
        });
    assertCode(shop.tenant(), InvariantCodes.ORDER_RESERVATION_ORPHAN);
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
              orderId.toString().toUpperCase(),
              reservation);
        });
    assertCode(shop.tenant(), InvariantCodes.ORDER_CANCELLED_ACTIVE_RESERVATION);
  }

  @Test
  void completedOrderKeepsActiveReservation() {
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
              ) VALUES (?, ?, ?, 'brk-done', 'COMPLETED', 'PAID', 'DELIVERED', 'NONE', 'PREPAID',
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
              orderId.toString().toUpperCase(),
              reservation);
        });
    assertCode(shop.tenant(), InvariantCodes.ORDER_TERMINAL_ACTIVE_RESERVATION);
  }

  @Test
  void readyToPickWithBlockingHold() {
    StockFixture.Shop shop = fixture.shop("ACTIVE");
    UUID channel = fixture.channelAccount(shop, "ACTIVE", "CONNECTED");
    UUID orderId = UuidV7.generate();
    fixture.inTenant(
        shop.tenant(),
        () ->
            jdbc.update(
                """
                INSERT INTO sales_order (
                  id, tenant_id, channel_account_id, external_order_id, order_status, payment_status,
                  fulfillment_status, hold_reason, payment_method, currency, subtotal, shipping_fee,
                  discount, grand_total, ordered_at, version
                ) VALUES (?, ?, ?, 'brk-hold', 'ACTIVE', 'PAID', 'READY_TO_PICK', 'OUT_OF_STOCK',
                  'PREPAID', 'THB', 1, 0, 0, 1, CURRENT_TIMESTAMP, 0)
                """,
                orderId,
                shop.tenant(),
                channel));
    assertCode(shop.tenant(), InvariantCodes.ORDER_READY_TO_PICK_HOLD);
  }

  @Test
  void readyToPickWithoutReservationCoverage() {
    StockFixture.Shop shop = fixture.shop("ACTIVE");
    UUID sku = fixture.sku(shop, 10);
    UUID channel = fixture.channelAccount(shop, "ACTIVE", "CONNECTED");
    fixture.channelListing(shop, channel, "L-brk", sku, true);
    UUID orderId = UuidV7.generate();
    fixture.inTenant(
        shop.tenant(),
        () -> {
          jdbc.update(
              """
              INSERT INTO sales_order (
                id, tenant_id, channel_account_id, external_order_id, order_status, payment_status,
                fulfillment_status, hold_reason, payment_method, currency, subtotal, shipping_fee,
                discount, grand_total, ordered_at, version
              ) VALUES (?, ?, ?, 'brk-cov', 'ACTIVE', 'PAID', 'READY_TO_PICK', 'NONE',
                'PREPAID', 'THB', 1, 0, 0, 1, CURRENT_TIMESTAMP, 0)
              """,
              orderId,
              shop.tenant(),
              channel);
          jdbc.update(
              """
              INSERT INTO order_line (
                id, tenant_id, order_id, external_line_id, external_sku_id, name, sku_id, qty,
                unit_price
              ) VALUES (?, ?, ?, '1', 'L-brk', 'Break line', ?, 2, 1)
              """,
              UuidV7.generate(),
              shop.tenant(),
              orderId,
              sku);
        });
    assertCode(shop.tenant(), InvariantCodes.ORDER_READY_TO_PICK_COVERAGE);
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
