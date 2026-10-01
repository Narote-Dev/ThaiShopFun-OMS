package com.thaishopfun.oms.stock;

import static org.assertj.core.api.Assertions.assertThat;

import com.thaishopfun.oms.auth.UuidV7;
import com.thaishopfun.oms.tenant.TenantContext;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Seeds catalog, warehouse, and stock rows with JDBC as {@code oms_app} under the tenant's context
 * (T07 owns the real catalog services). Opening stock always gets an {@code OPENING_BALANCE} ledger
 * row, so the ledger-sum invariant holds from the start.
 */
final class StockFixture {

  record Shop(UUID tenant, UUID product, UUID warehouse) {}

  private final JdbcTemplate jdbc;
  private final TransactionTemplate tx;

  StockFixture(JdbcTemplate jdbc, PlatformTransactionManager transactions) {
    this.jdbc = jdbc;
    this.tx = new TransactionTemplate(transactions);
  }

  <T> T inTenant(UUID tenantId, Supplier<T> work) {
    TenantContext.set(tenantId, null);
    try {
      return tx.execute(status -> work.get());
    } finally {
      TenantContext.clear();
    }
  }

  void inTenant(UUID tenantId, Runnable work) {
    inTenant(
        tenantId,
        () -> {
          work.run();
          return null;
        });
  }

  /** A tenant with one product and a default warehouse. */
  Shop shop(String entitlementStatus) {
    return shop(entitlementStatus, true);
  }

  Shop shop(String entitlementStatus, boolean defaultWarehouse) {
    UUID tenant = UuidV7.generate();
    UUID product = UuidV7.generate();
    UUID warehouse = UuidV7.generate();
    inTenant(
        tenant,
        () -> {
          jdbc.update(
              "INSERT INTO tenant (id, name, tsf_shop_id, membership_tier, entitlement_status, "
                  + "ent_ver) VALUES (?, 'Shop', ?, 'PRO', ?, 1)",
              tenant,
              "shop-" + tenant,
              entitlementStatus);
          jdbc.update(
              "INSERT INTO product (id, tenant_id, name, status) VALUES (?, ?, 'P', 'ACTIVE')",
              product,
              tenant);
          jdbc.update(
              "INSERT INTO warehouse (id, tenant_id, code, name, is_default) "
                  + "VALUES (?, ?, ?, 'Main', ?)",
              warehouse,
              tenant,
              "WH-" + warehouse,
              defaultWarehouse);
        });
    return new Shop(tenant, product, warehouse);
  }

  UUID warehouse(Shop shop) {
    UUID warehouse = UuidV7.generate();
    inTenant(
        shop.tenant(),
        () -> {
          jdbc.update(
              "INSERT INTO warehouse (id, tenant_id, code, name, is_default) "
                  + "VALUES (?, ?, ?, 'Second', false)",
              warehouse,
              shop.tenant(),
              "WH-" + warehouse);
        });
    return warehouse;
  }

  /** A plain SKU with an inventory row in the shop's warehouse and opening stock. */
  UUID sku(Shop shop, int onHand) {
    UUID sku = skuWithoutStock(shop);
    stock(shop, sku, shop.warehouse(), onHand);
    return sku;
  }

  UUID skuWithoutStock(Shop shop) {
    UUID sku = UuidV7.generate();
    inTenant(
        shop.tenant(),
        () -> {
          jdbc.update(
              "INSERT INTO sku (id, tenant_id, product_id, sku_code, name, is_bundle) "
                  + "VALUES (?, ?, ?, ?, 'SKU', false)",
              sku,
              shop.tenant(),
              shop.product(),
              "SKU-" + sku);
        });
    return sku;
  }

  void stock(Shop shop, UUID sku, UUID warehouse, int onHand) {
    inTenant(
        shop.tenant(),
        () -> {
          jdbc.update(
              "INSERT INTO inventory (id, tenant_id, sku_id, warehouse_id, on_hand, reserved) "
                  + "VALUES (?, ?, ?, ?, ?, 0)",
              UuidV7.generate(),
              shop.tenant(),
              sku,
              warehouse,
              onHand);
          ledger(shop, sku, warehouse, onHand, "OPENING_BALANCE");
        });
  }

  /** A bundle SKU with the given components (component sku id to qty per bundle). */
  UUID bundle(Shop shop, Map<UUID, Integer> components) {
    UUID bundle = UuidV7.generate();
    inTenant(
        shop.tenant(),
        () -> {
          jdbc.update(
              "INSERT INTO sku (id, tenant_id, product_id, sku_code, name, is_bundle) "
                  + "VALUES (?, ?, ?, ?, 'Bundle', true)",
              bundle,
              shop.tenant(),
              shop.product(),
              "BUNDLE-" + bundle);
          for (Map.Entry<UUID, Integer> component : components.entrySet()) {
            jdbc.update(
                "INSERT INTO sku_bundle_component (tenant_id, bundle_sku_id, component_sku_id, qty) "
                    + "VALUES (?, ?, ?, ?)",
                shop.tenant(),
                bundle,
                component.getKey(),
                component.getValue());
          }
        });
    return bundle;
  }

  /** Receives stock (on_hand += qty) with a RECEIVE ledger row, like a posted document would. */
  void receive(Shop shop, UUID sku, int qty) {
    inTenant(
        shop.tenant(),
        () -> {
          jdbc.update(
              "UPDATE inventory SET on_hand = on_hand + ?, stock_version = stock_version + 1 "
                  + "WHERE sku_id = ? AND warehouse_id = ?",
              qty,
              sku,
              shop.warehouse());
          ledger(shop, sku, shop.warehouse(), qty, "RECEIVE");
        });
  }

  UUID listing(Shop shop, UUID sku, int safetyBuffer) {
    UUID account = UuidV7.generate();
    UUID listing = UuidV7.generate();
    inTenant(
        shop.tenant(),
        () -> {
          jdbc.update(
              "INSERT INTO channel_account (id, tenant_id, channel, external_shop_id, status) "
                  + "VALUES (?, ?, 'TSF', ?, 'CONNECTED')",
              account,
              shop.tenant(),
              "ext-" + account);
          jdbc.update(
              "INSERT INTO channel_listing (id, tenant_id, channel_account_id, sku_id, "
                  + "external_sku_id, safety_buffer) VALUES (?, ?, ?, ?, ?, ?)",
              listing,
              shop.tenant(),
              account,
              sku,
              "ext-sku-" + listing,
              safetyBuffer);
        });
    return listing;
  }

  int reserved(Shop shop, UUID sku) {
    return inventoryValue(shop, sku, "reserved");
  }

  int onHand(Shop shop, UUID sku) {
    return inventoryValue(shop, sku, "on_hand");
  }

  long reservations(Shop shop, String status) {
    return inTenant(
        shop.tenant(),
        () ->
            jdbc.queryForObject(
                "SELECT count(*) FROM stock_reservation WHERE status = ?", Long.class, status));
  }

  long ledger(Shop shop, String reason) {
    return inTenant(
        shop.tenant(),
        () ->
            jdbc.queryForObject(
                "SELECT count(*) FROM inventory_ledger WHERE reason = ?", Long.class, reason));
  }

  long ledgerRows(Shop shop) {
    return inTenant(
        shop.tenant(),
        () -> jdbc.queryForObject("SELECT count(*) FROM inventory_ledger", Long.class));
  }

  List<Map<String, Object>> groupRows(Shop shop, UUID groupId) {
    return inTenant(
        shop.tenant(),
        () ->
            jdbc.queryForList(
                "SELECT owner_type, owner_ref, status, expires_at, qty, sku_id "
                    + "FROM stock_reservation WHERE reservation_group_id = ? ORDER BY sku_id",
                groupId));
  }

  /**
   * The T00 stock invariants for one tenant: {@code 0 <= reserved <= on_hand}; ACTIVE reservation
   * qty per inventory row equals {@code reserved}; ledger delta sums equal {@code on_hand} and
   * {@code reserved}.
   */
  void assertInvariants(Shop shop) {
    inTenant(
        shop.tenant(),
        () -> {
          assertThat(
                  jdbc.queryForObject(
                      "SELECT count(*) FROM inventory WHERE reserved < 0 OR reserved > on_hand",
                      Long.class))
              .as("0 <= reserved <= on_hand")
              .isZero();
          assertThat(
                  jdbc.queryForList(
                      """
                      SELECT i.id
                      FROM inventory AS i
                      LEFT JOIN (
                        SELECT sku_id, warehouse_id, sum(qty) AS qty
                        FROM stock_reservation
                        WHERE status = 'ACTIVE'
                        GROUP BY sku_id, warehouse_id
                      ) AS r USING (sku_id, warehouse_id)
                      WHERE coalesce(r.qty, 0) <> i.reserved
                      """,
                      UUID.class))
              .as("ACTIVE reservation qty = reserved")
              .isEmpty();
          assertThat(
                  jdbc.queryForList(
                      """
                      SELECT i.id
                      FROM inventory AS i
                      LEFT JOIN (
                        SELECT sku_id, warehouse_id, sum(delta_on_hand) AS on_hand,
                               sum(delta_reserved) AS reserved
                        FROM inventory_ledger
                        GROUP BY sku_id, warehouse_id
                      ) AS l USING (sku_id, warehouse_id)
                      WHERE coalesce(l.on_hand, 0) <> i.on_hand
                         OR coalesce(l.reserved, 0) <> i.reserved
                      """,
                      UUID.class))
              .as("ledger sums = on_hand and reserved")
              .isEmpty();
        });
  }

  private int inventoryValue(Shop shop, UUID sku, String column) {
    return inTenant(
        shop.tenant(),
        () ->
            jdbc.queryForObject(
                "SELECT " + column + " FROM inventory WHERE sku_id = ? AND warehouse_id = ?",
                Integer.class,
                sku,
                shop.warehouse()));
  }

  private void ledger(Shop shop, UUID sku, UUID warehouse, int qty, String reason) {
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
            warehouse);
    jdbc.update(
        "INSERT INTO inventory_ledger (id, tenant_id, sku_id, warehouse_id, delta_on_hand, "
            + "delta_reserved, reason, actor, ledger_seq) VALUES (?, ?, ?, ?, ?, 0, ?, 'test', ?)",
        UuidV7.generate(),
        shop.tenant(),
        sku,
        warehouse,
        qty,
        reason,
        seq);
  }
}
