package com.thaishopfun.oms.fulfillment;

import com.thaishopfun.oms.auth.UuidV7;
import com.thaishopfun.oms.catalog.CatalogHttp;
import com.thaishopfun.oms.order.SalesOrder;
import java.math.BigDecimal;
import java.util.UUID;

/** Seeds catalog rows for pick/pack API tests. */
public final class FulfillmentTestSupport {

  private FulfillmentTestSupport() {}

  public record SkuSeed(UUID skuId, String skuCode, String barcode, UUID warehouseId) {}

  public static SkuSeed seedSku(UUID tenantId, String skuCode, String barcode) {
    UUID productId = UuidV7.generate();
    UUID skuId = UuidV7.generate();
    UUID warehouseId = UuidV7.generate();
    String whCode = "WH-" + warehouseId.toString().substring(0, 8);
    CatalogHttp.execute(
        """
        INSERT INTO warehouse (id, tenant_id, code, name, is_default)
        VALUES (?, ?, ?, 'Main', true)
        """,
        warehouseId,
        tenantId,
        whCode);
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
        "Test SKU " + skuCode,
        barcode);
    return new SkuSeed(skuId, skuCode, barcode, warehouseId);
  }

  public static void addLine(SalesOrder order, UUID skuId, int qty) {
    CatalogHttp.execute(
        """
        INSERT INTO order_line (
          id, tenant_id, order_id, sku_id, external_line_id, external_sku_id, name, qty,
          unit_price, discount, line_total
        ) VALUES (?, ?, ?, ?, 'L1', 'ext-sku', 'Line', ?, 100, 0, ?)
        """,
        UuidV7.generate(),
        order.tenantId(),
        order.id(),
        skuId,
        qty,
        BigDecimal.valueOf(100L * qty));
  }
}
