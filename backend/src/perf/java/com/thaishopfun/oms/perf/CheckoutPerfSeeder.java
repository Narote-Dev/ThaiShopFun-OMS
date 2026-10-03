package com.thaishopfun.oms.perf;

import com.thaishopfun.oms.auth.UuidV7;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.UUID;

/** Seeds one ACTIVE shop (~200 listings) and a SHADOW shop for Gatling. */
public final class CheckoutPerfSeeder {

  private CheckoutPerfSeeder() {}

  public static void main(String[] args) throws Exception {
    String url = System.getenv().getOrDefault("SPRING_DATASOURCE_URL", "jdbc:postgresql://127.0.0.1:5432/oms");
    String user = System.getenv().getOrDefault("SPRING_FLYWAY_USER", "oms");
    String password = System.getenv().getOrDefault("SPRING_FLYWAY_PASSWORD", "oms");
    try (Connection conn = DriverManager.getConnection(url, user, password)) {
      conn.setAutoCommit(false);
      seedShop(conn, "perf-active", "ACTIVE", "ACTIVE", 200, true);
      seedShop(conn, "perf-shadow", "SHADOW", "ACTIVE", 20, false);
      conn.commit();
    }
  }

  private static void seedShop(
      Connection conn,
      String tsfShopId,
      String mode,
      String entitlement,
      int listings,
      boolean bundles)
      throws Exception {
    UUID tenant = UuidV7.generate();
    UUID product = UuidV7.generate();
    UUID warehouse = UuidV7.generate();
    UUID account = UuidV7.generate();
    exec(
        conn,
        "INSERT INTO tenant (id, name, tsf_shop_id, membership_tier, entitlement_status, ent_ver) "
            + "VALUES (?, ?, ?, 'PRO', ?, 1)",
        tenant,
        "Perf " + tsfShopId,
        tsfShopId,
        entitlement);
    exec(
        conn,
        "INSERT INTO product (id, tenant_id, name, status) VALUES (?, ?, 'Perf', 'ACTIVE')",
        product,
        tenant);
    exec(
        conn,
        "INSERT INTO warehouse (id, tenant_id, code, name, is_default) VALUES (?, ?, ?, 'Main', true)",
        warehouse,
        tenant,
        "WH-" + warehouse);
    exec(
        conn,
        "INSERT INTO channel_account (id, tenant_id, channel, external_shop_id, status, mode) "
            + "VALUES (?, ?, 'TSF', ?, 'CONNECTED', ?)",
        account,
        tenant,
        "ext-" + account,
        mode);
    UUID bundleSku = null;
    if (bundles) {
      UUID a = insertSku(conn, tenant, product, warehouse, 500);
      UUID b = insertSku(conn, tenant, product, warehouse, 500);
      bundleSku = UuidV7.generate();
      exec(
          conn,
          "INSERT INTO sku (id, tenant_id, product_id, sku_code, name, is_bundle) "
              + "VALUES (?, ?, ?, 'BUNDLE', 'Bundle', true)",
          bundleSku,
          tenant,
          product);
      exec(
          conn,
          "INSERT INTO sku_bundle_component (tenant_id, bundle_sku_id, component_sku_id, qty) "
              + "VALUES (?, ?, ?, 1), (?, ?, ?, 1)",
          tenant,
          bundleSku,
          a,
          tenant,
          bundleSku,
          b);
      exec(
          conn,
          "INSERT INTO channel_listing (id, tenant_id, channel_account_id, sku_id, external_sku_id, "
              + "stock_control, mapping_source, mapped_at) "
              + "VALUES (?, ?, ?, ?, 'L-bundle', true, 'MANUAL', now())",
          UuidV7.generate(),
          tenant,
          account,
          bundleSku);
    }
    for (int i = 0; i < listings; i++) {
      UUID sku = insertSku(conn, tenant, product, warehouse, 10_000);
      exec(
          conn,
          "INSERT INTO channel_listing (id, tenant_id, channel_account_id, sku_id, external_sku_id, "
              + "stock_control, mapping_source, mapped_at) "
              + "VALUES (?, ?, ?, ?, ?, true, 'MANUAL', now())",
          UuidV7.generate(),
          tenant,
          account,
          sku,
          "L-" + i);
    }
  }

  private static UUID insertSku(
      Connection conn, UUID tenant, UUID product, UUID warehouse, int onHand) throws Exception {
    UUID sku = UuidV7.generate();
    exec(
        conn,
        "INSERT INTO sku (id, tenant_id, product_id, sku_code, name, is_bundle) "
            + "VALUES (?, ?, ?, ?, 'S', false)",
        sku,
        tenant,
        product,
        "SKU-" + sku);
    UUID inventory = UuidV7.generate();
    exec(
        conn,
        "INSERT INTO inventory (id, tenant_id, sku_id, warehouse_id, on_hand, reserved) "
            + "VALUES (?, ?, ?, ?, ?, 0)",
        inventory,
        tenant,
        sku,
        warehouse,
        onHand);
    return sku;
  }

  private static void exec(Connection conn, String sql, Object... params) throws Exception {
    try (PreparedStatement statement = conn.prepareStatement(sql)) {
      for (int i = 0; i < params.length; i++) {
        statement.setObject(i + 1, params[i]);
      }
      statement.executeUpdate();
    }
  }
}
