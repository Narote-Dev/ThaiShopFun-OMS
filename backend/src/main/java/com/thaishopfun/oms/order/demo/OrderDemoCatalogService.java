package com.thaishopfun.oms.order.demo;

import com.thaishopfun.oms.auth.UuidV7;
import com.thaishopfun.oms.tenant.TenantContext;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Idempotent catalog rows for mock-tsf demo orders (shop_active). */
@Service
@Profile({"local", "e2e", "test"})
public class OrderDemoCatalogService {

  private static final String SHOP = "shop_active";

  private final JdbcTemplate jdbc;
  private final TransactionTemplate transactionTemplate;

  public OrderDemoCatalogService(JdbcTemplate jdbc, TransactionTemplate transactionTemplate) {
    this.jdbc = jdbc;
    this.transactionTemplate = transactionTemplate;
  }

  record ShopContext(UUID tenantId, UUID channelAccountId, UUID productId, UUID warehouseId) {}

  public Map<String, Object> ensureDemoCatalog() {
    boolean clearTenant = false;
    UUID tenantId = TenantContext.tenantId();
    if (tenantId == null) {
      tenantId = resolveTsfShopTenantId();
      if (tenantId == null) {
        throw new DemoCatalogTenantMissingException();
      }
      TenantContext.set(tenantId, null);
      clearTenant = true;
    }
    try {
      return transactionTemplate.execute(status -> seedCatalogInTransaction());
    } finally {
      if (clearTenant) {
        TenantContext.clear();
      }
    }
  }

  private UUID resolveTsfShopTenantId() {
    List<UUID> ids =
        jdbc.query(
            "SELECT resolve_tenant(?, ?)",
            (rs, rowNum) -> rs.getObject(1, UUID.class),
            "TSF",
            SHOP);
    return ids.isEmpty() ? null : ids.get(0);
  }

  private Map<String, Object> seedCatalogInTransaction() {
    ShopContext shop = resolveShop();
    enforceStockOnDemoChannel(shop);
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
    List<UUID> tsfAccounts = listTsfChannelAccounts(shop.tenantId());
    List<UUID> demoOrderAccounts =
        jdbc.query(
            """
            SELECT DISTINCT so.channel_account_id
            FROM sales_order so
            WHERE so.tenant_id = ? AND so.external_order_id LIKE 'DEMO-%'
            """,
            (rs, rowNum) -> rs.getObject("channel_account_id", UUID.class), shop.tenantId());
    java.util.LinkedHashSet<UUID> allAccounts = new java.util.LinkedHashSet<>(tsfAccounts);
    allAccounts.addAll(demoOrderAccounts);
    for (UUID channelAccountId : allAccounts) {
      ensureListing(
          shop, channelAccountId, "L-demo-ready", ready, true, "DEMO-SKU-READY", "Demo ready");
      ensureListing(shop, channelAccountId, "L-demo-cod", cod, true, "DEMO-SKU-COD", "Demo COD");
      ensureListing(shop, channelAccountId, "L-demo-oos", oos, true, "DEMO-SKU-OOS", "Demo OOS");
      ensureListing(
          shop,
          channelAccountId,
          "L-demo-bundle",
          bundleEmpty,
          true,
          "DEMO-SKU-BUNDLE-EMPTY",
          "Demo bundle");
      ensureListing(
          shop, channelAccountId, "L-demo-cancel", cod, true, "DEMO-SKU-COD", "Demo cancel");
      ensureListing(
          shop,
          channelAccountId,
          "L-demo-missing",
          null,
          false,
          "DEMO-SKU-MISSING",
          "Demo unmapped listing");
    }
    UUID missingListingAccountId = shop.channelAccountId();
    List<UUID> unmappedOrderAccounts =
        jdbc.query(
            """
            SELECT channel_account_id FROM sales_order
            WHERE tenant_id = ? AND external_order_id = 'DEMO-UNMAPPED'
            LIMIT 1
            """,
            (rs, rowNum) -> rs.getObject("channel_account_id", UUID.class),
            shop.tenantId());
    if (!unmappedOrderAccounts.isEmpty()) {
      missingListingAccountId = unmappedOrderAccounts.get(0);
    }
    UUID missingListingId =
        jdbc.queryForObject(
            """
            SELECT id FROM channel_listing
            WHERE channel_account_id = ? AND external_sku_id = 'L-demo-missing'
            """,
            UUID.class,
            missingListingAccountId);
    return Map.of(
        "status",
        "OK",
        "tenant_id",
        shop.tenantId().toString(),
        "channel_account_id",
        shop.channelAccountId().toString(),
        "L_demo_missing_listing_id",
        missingListingId.toString(),
        "listings",
        List.of(
            "L-demo-ready",
            "L-demo-cod",
            "L-demo-oos",
            "L-demo-bundle",
            "L-demo-cancel",
            "L-demo-missing"));
  }

  private ShopContext resolveShop() {
    UUID tenantId = TenantContext.requireTenantId();
    ShopContext base = resolveForTenant(tenantId);
    if (base == null) {
      throw new DemoCatalogTenantMissingException();
    }
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

  /** Demo intake must exercise stock holds; provisioned TSF accounts default to OBSERVE. */
  private void enforceStockOnDemoChannel(ShopContext shop) {
    jdbc.update(
        """
        UPDATE channel_account
        SET mode = 'ACTIVE'
        WHERE tenant_id = ? AND id = ? AND mode = 'OBSERVE'
        """,
        shop.tenantId(),
        shop.channelAccountId());
  }

  private ShopContext resolveForTenant(UUID tenantId) {
    List<ShopContext> rows =
        jdbc.query(
            """
            SELECT t.id AS tenant_id, ca.id AS channel_account_id
            FROM tenant t
            JOIN channel_account ca
              ON ca.tenant_id = t.id
             AND ca.channel = 'TSF'
             AND ca.external_shop_id = t.tsf_shop_id
            WHERE t.id = ?
            LIMIT 1
            """,
            (rs, rowNum) ->
                new ShopContext(
                    rs.getObject("tenant_id", UUID.class),
                    rs.getObject("channel_account_id", UUID.class),
                    null,
                    null),
            tenantId);
    return rows.isEmpty() ? null : rows.get(0);
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
          """
          UPDATE inventory
          SET on_hand = GREATEST(?, reserved), stock_version = stock_version + 1
          WHERE tenant_id = ? AND sku_id = ? AND warehouse_id = ?
          """,
          onHand,
          shop.tenantId(),
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

  private List<UUID> listTsfChannelAccounts(UUID tenantId) {
    return jdbc.query(
        "SELECT id FROM channel_account WHERE tenant_id = ? AND channel = 'TSF' ORDER BY created_at",
        (rs, rowNum) -> rs.getObject("id", UUID.class),
        tenantId);
  }

  private void ensureListing(
      ShopContext shop,
      UUID channelAccountId,
      String externalSkuId,
      UUID skuId,
      boolean mapped,
      String sellerSku,
      String name) {
    java.time.OffsetDateTime mappedAt = mapped ? java.time.OffsetDateTime.now() : null;
    jdbc.update(
        """
        INSERT INTO channel_listing (
          id, tenant_id, channel_account_id, sku_id, external_sku_id, seller_sku, name,
          stock_control, mapping_source, mapped_at, removed_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL)
        ON CONFLICT (channel_account_id, external_sku_id) DO UPDATE SET
          seller_sku = EXCLUDED.seller_sku,
          name = EXCLUDED.name,
          stock_control = EXCLUDED.stock_control,
          removed_at = NULL,
          updated_at = now(),
          sku_id = CASE
            WHEN EXCLUDED.sku_id IS NOT NULL THEN EXCLUDED.sku_id
            ELSE channel_listing.sku_id
          END,
          mapping_source = CASE
            WHEN EXCLUDED.sku_id IS NOT NULL THEN EXCLUDED.mapping_source
            ELSE channel_listing.mapping_source
          END,
          mapped_at = CASE
            WHEN EXCLUDED.sku_id IS NOT NULL THEN EXCLUDED.mapped_at
            ELSE channel_listing.mapped_at
          END
        """,
        UuidV7.generate(),
        shop.tenantId(),
        channelAccountId,
        mapped ? skuId : null,
        externalSkuId,
        sellerSku,
        name,
        true,
        mapped ? "MANUAL" : null,
        mappedAt);
  }
}
