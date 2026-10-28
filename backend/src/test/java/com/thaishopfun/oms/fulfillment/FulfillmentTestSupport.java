package com.thaishopfun.oms.fulfillment;

import com.thaishopfun.oms.auth.UuidV7;
import com.thaishopfun.oms.catalog.CatalogHttp;
import com.thaishopfun.oms.order.SalesOrder;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.UUID;

/** Seeds catalog rows for pick/pack API tests. */
public final class FulfillmentTestSupport {

  private FulfillmentTestSupport() {}

  public record SkuSeed(UUID skuId, String skuCode, String barcode, UUID warehouseId) {}

  public static UUID ensureDefaultWarehouse(UUID tenantId) {
    try (var admin = com.thaishopfun.oms.auth.AuthTestSupport.admin();
        var statement =
            admin.prepareStatement(
                """
                SELECT id FROM warehouse WHERE tenant_id = ? AND is_default = true LIMIT 1
                """)) {
      statement.setObject(1, tenantId);
      try (var rows = statement.executeQuery()) {
        if (rows.next()) {
          return rows.getObject(1, UUID.class);
        }
      }
    } catch (SQLException ex) {
      throw new IllegalStateException(ex);
    }
    UUID warehouseId = UuidV7.generate();
    String whCode = "MAIN-" + warehouseId.toString().substring(0, 6);
    CatalogHttp.execute(
        """
        INSERT INTO warehouse (id, tenant_id, code, name, is_default)
        VALUES (?, ?, ?, 'Main', true)
        """,
        warehouseId,
        tenantId,
        whCode);
    return warehouseId;
  }

  public static SkuSeed seedSku(UUID tenantId, String skuCode, String barcode) {
    UUID warehouseId = ensureDefaultWarehouse(tenantId);
    UUID productId = UuidV7.generate();
    UUID skuId = UuidV7.generate();
    CatalogHttp.execute(
        """
        INSERT INTO product (id, tenant_id, name, status)
        VALUES (?, ?, 'Test product', 'ACTIVE')
        """,
        productId,
        tenantId);
    CatalogHttp.execute(
        """
        INSERT INTO sku (id, tenant_id, product_id, sku_code, name, barcode, is_bundle)
        VALUES (?, ?, ?, ?, ?, ?, false)
        """,
        skuId,
        tenantId,
        productId,
        skuCode,
        "เสื้อ " + skuCode,
        barcode);
    return new SkuSeed(skuId, skuCode, barcode, warehouseId);
  }

  public static void addLine(SalesOrder order, UUID skuId, int qty) {
    addLine(order, skuId, qty, "L1");
  }

  public static void addLine(SalesOrder order, UUID skuId, int qty, String externalLineId) {
    CatalogHttp.execute(
        """
        INSERT INTO order_line (
          id, tenant_id, order_id, sku_id, external_line_id, external_sku_id, name, qty,
          unit_price, discount, line_total
        ) VALUES (?, ?, ?, ?, ?, 'ext-sku', 'Line', ?, 100, 0, ?)
        """,
        UuidV7.generate(),
        order.tenantId(),
        order.id(),
        skuId,
        externalLineId,
        qty,
        BigDecimal.valueOf(100L * qty));
  }

  public static void seedInventory(UUID tenantId, UUID skuId, UUID warehouseId, int onHand) {
    UUID inventoryId = UuidV7.generate();
    CatalogHttp.execute(
        """
        INSERT INTO inventory (id, tenant_id, sku_id, warehouse_id, on_hand, reserved, ledger_seq)
        VALUES (?, ?, ?, ?, ?, 0, 0)
        ON CONFLICT (tenant_id, sku_id, warehouse_id) DO UPDATE
          SET on_hand = EXCLUDED.on_hand
        """,
        inventoryId,
        tenantId,
        skuId,
        warehouseId,
        onHand);
    long seq =
        CatalogHttp.textLong(
            """
            UPDATE inventory SET ledger_seq = ledger_seq + 1
            WHERE tenant_id = ? AND sku_id = ? AND warehouse_id = ?
            RETURNING ledger_seq
            """,
            tenantId,
            skuId,
            warehouseId);
    CatalogHttp.execute(
        """
        INSERT INTO inventory_ledger (
          id, tenant_id, sku_id, warehouse_id, delta_on_hand, delta_reserved, reason, actor, ledger_seq
        ) VALUES (?, ?, ?, ?, ?, 0, 'OPENING_BALANCE', 'test', ?)
        """,
        UuidV7.generate(),
        tenantId,
        skuId,
        warehouseId,
        onHand,
        seq);
  }
}
