package com.thaishopfun.oms.order.demo;

import com.thaishopfun.oms.auth.UuidV7;
import com.thaishopfun.oms.tenant.TenantContext;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Idempotent catalog rows for mock-tsf demo orders (shop_active). */
@Service
@Profile({"local", "e2e", "test"})
public class OrderDemoCatalogService {

  private static final String SHOP = "shop_active";

  private final JdbcTemplate jdbc;

  public OrderDemoCatalogService(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  record ShopContext(UUID tenantId, UUID channelAccountId, UUID productId, UUID warehouseId) {}

  @Transactional
  public Map<String, Object> ensureDemoCatalog() {
    ShopContext shop = resolveShop();
    if (shop == null) {
      return Map.of("status", "SKIPPED", "reason", "shop_active tenant not provisioned");
    }
    TenantContext.set(shop.tenantId(), null);
    try {
      ensureWarehouse(shop);
      UUID ready = ensureSku(shop, "DEMO-SKU-READY", false);
      UUID cod = ensureSku(shop, "DEMO-SKU-COD", false);
      UUID oos = ensureSku(shop, "DEMO-SKU-OOS", false);
      UUID bundleEmpty = ensureSku(shop, "DEMO-SKU-BUNDLE-EMPTY", true);
      UUID component = ensureSku(shop, "DEMO-SKU-COMP", false);
      stock(shop, ready, 50);
      stock(shop, cod, 50);
      stock(shop, oos, 0);
      stock(shop, component, 50);
      ensureListing(shop, "L-demo-ready", ready, true);
      ensureListing(shop, "L-demo-cod", cod, true);
      ensureListing(shop, "L-demo-oos", oos, true);
      ensureListing(shop, "L-demo-bundle", bundleEmpty, true);
      ensureListing(shop, "L-demo-cancel", cod, true);
      ensureListing(shop, "L-demo-missing", null, false);
      return Map.of(
          "status",
          "OK",
          "tenant_id",
          shop.tenantId().toString(),
          "listings",
          List.of(
              "L-demo-ready",
              "L-demo-cod",
              "L-demo-oos",
              "L-demo-bundle",
              "L-demo-cancel",
              "L-demo-missing"));
    } finally {
      TenantContext.clear();
    }
  }

  private ShopContext resolveShop() {
    List<ShopContext> rows =
        jdbc.query(
            """
            SELECT t.id AS tenant_id, ca.id AS channel_account_id
            FROM tenant t
            JOIN channel_account ca ON ca.tenant_id = t.id AND ca.channel = 'TSF'
            WHERE t.tsf_shop_id = ?
            LIMIT 1
            """,
            (rs, rowNum) ->
                new ShopContext(
                    rs.getObject("tenant_id", UUID.class),
                    rs.getObject("channel_account_id", UUID.class),
                    null,
                    null),
            SHOP);
    if (rows.isEmpty()) {
      return null;
    }
    ShopContext base = rows.get(0);
    List<UUID> products =
        jdbc.query(
            """
            SELECT id FROM product WHERE tenant_id = ? ORDER BY created_at NULLS LAST LIMIT 1
            """,
            (rs, rowNum) -> rs.getObject("id", UUID.class),
            base.tenantId());
    UUID product = products.isEmpty() ? null : products.get(0);
    if (product == null) {
      product = UuidV7.generate();
      jdbc.update(
          "INSERT INTO product (id, tenant_id, name, status) VALUES (?, ?, 'Demo catalog', 'ACTIVE')",
          product,
          base.tenantId());
    }
    return new ShopContext(base.tenantId(), base.channelAccountId(), product, null);
  }

  private void ensureWarehouse(ShopContext shop) {
    List<UUID> warehouses =
        jdbc.query(
            "SELECT id FROM warehouse WHERE tenant_id = ? AND is_default = true LIMIT 1",
            (rs, rowNum) -> rs.getObject("id", UUID.class),
            shop.tenantId());
    UUID warehouse = warehouses.isEmpty() ? null : warehouses.get(0);
    if (warehouse == null) {
      warehouse = UuidV7.generate();
      jdbc.update(
          "INSERT INTO warehouse (id, tenant_id, code, name, is_default) VALUES (?, ?, 'MAIN', 'Main', true)",
          warehouse,
          shop.tenantId());
    }
  }

  private UUID ensureSku(ShopContext shop, String code, boolean bundle) {
    List<UUID> existing =
        jdbc.query(
            "SELECT id FROM sku WHERE tenant_id = ? AND sku_code = ?",
            (rs, rowNum) -> rs.getObject("id", UUID.class),
            shop.tenantId(),
            code);
    if (!existing.isEmpty()) {
      return existing.get(0);
    }
    UUID id = UuidV7.generate();
    jdbc.update(
        "INSERT INTO sku (id, tenant_id, product_id, sku_code, name, is_bundle) VALUES (?, ?, ?, ?, ?, ?)",
        id,
        shop.tenantId(),
        shop.productId(),
        code,
        code,
        bundle);
    return id;
  }

  private void stock(ShopContext shop, UUID skuId, int onHand) {
    UUID warehouse =
        jdbc.queryForObject(
            "SELECT id FROM warehouse WHERE tenant_id = ? AND is_default = true LIMIT 1",
            UUID.class,
            shop.tenantId());
    Long count =
        jdbc.queryForObject(
            "SELECT count(*) FROM inventory WHERE tenant_id = ? AND sku_id = ? AND warehouse_id = ?",
            Long.class,
            shop.tenantId(),
            skuId,
            warehouse);
    if (count != null && count > 0) {
      jdbc.update(
          "UPDATE inventory SET on_hand = ?, stock_version = stock_version + 1 WHERE sku_id = ? AND warehouse_id = ?",
          onHand,
          skuId,
          warehouse);
      return;
    }
    jdbc.update(
        "INSERT INTO inventory (id, tenant_id, sku_id, warehouse_id, on_hand, reserved) VALUES (?, ?, ?, ?, ?, 0)",
        UuidV7.generate(),
        shop.tenantId(),
        skuId,
        warehouse,
        onHand);
  }

  private void ensureListing(ShopContext shop, String externalSkuId, UUID skuId, boolean mapped) {
    Long count =
        jdbc.queryForObject(
            "SELECT count(*) FROM channel_listing WHERE tenant_id = ? AND external_sku_id = ?",
            Long.class,
            shop.tenantId(),
            externalSkuId);
    if (count != null && count > 0) {
      return;
    }
    jdbc.update(
        "INSERT INTO channel_listing (id, tenant_id, channel_account_id, sku_id, external_sku_id, stock_control) "
            + "VALUES (?, ?, ?, ?, ?, ?)",
        UuidV7.generate(),
        shop.tenantId(),
        shop.channelAccountId(),
        mapped ? skuId : null,
        externalSkuId,
        true);
  }
}
