package com.thaishopfun.oms.order.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.thaishopfun.oms.auth.AuthTestSupport;
import com.thaishopfun.oms.auth.UuidV7;
import com.thaishopfun.oms.catalog.CatalogHttp;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** Listing list must return rows under RLS via tenant-scoped read transactions. */
class StockControlApiTest extends OrderIntegrationTest {

  @Autowired JdbcTemplate jdbc;

  CatalogHttp.Shop shop;
  UUID accountId;
  UUID skuId;
  UUID listingId;

  @BeforeEach
  void seed() throws Exception {
    try (Connection admin = AuthTestSupport.admin();
        var statement = admin.createStatement()) {
      statement.execute("SET session_replication_role = replica");
      statement.execute("TRUNCATE TABLE channel_listing, sku, product CASCADE");
      statement.execute("SET session_replication_role = DEFAULT");
    }
    shop = http.catalog().shop();
    accountId = OrderFixture.shopFor(shop).channelAccountId();
    UUID productId = UuidV7.generate();
    skuId = UuidV7.generate();
    listingId = UuidV7.generate();
    try (Connection admin = AuthTestSupport.admin()) {
      try (PreparedStatement product =
          admin.prepareStatement(
              "INSERT INTO product (id, tenant_id, name, status) VALUES (?, ?, 'API product', 'ACTIVE')")) {
        product.setObject(1, productId);
        product.setObject(2, shop.tenantId());
        product.executeUpdate();
      }
      try (PreparedStatement sku =
          admin.prepareStatement(
              """
              INSERT INTO sku (id, tenant_id, product_id, sku_code, name, is_bundle)
              VALUES (?, ?, ?, 'API-SKU', 'API sku', false)
              """)) {
        sku.setObject(1, skuId);
        sku.setObject(2, shop.tenantId());
        sku.setObject(3, productId);
        sku.executeUpdate();
      }
      try (PreparedStatement statement =
          admin.prepareStatement(
              """
              INSERT INTO channel_listing (
                id, tenant_id, channel_account_id, external_sku_id, seller_sku, name, stock_control
              ) VALUES (?, ?, ?, 'L-api', 'API-SKU', 'API listing', true)
              """)) {
        statement.setObject(1, listingId);
        statement.setObject(2, shop.tenantId());
        statement.setObject(3, accountId);
        statement.executeUpdate();
      }
    }
  }

  @Autowired com.thaishopfun.oms.stockpush.StockPushService pushes;

  String path() {
    return "/api/v1/channel-listings/" + listingId + "/stock-control";
  }

  void sql(String sql, Object... args) throws Exception {
    try (var c = AuthTestSupport.admin();
        var ps = c.prepareStatement(sql)) {
      for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
      ps.executeUpdate();
    }
  }

  long audits() throws Exception {
    try (var c = AuthTestSupport.admin();
        var ps =
            c.prepareStatement(
                "SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'CHANNEL_LISTING_STOCK_CONTROL_CHANGED'")) {
      ps.setObject(1, shop.tenantId());
      try (var rs = ps.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }

  @Test
  void bulkIsAtomicAuditedAndIdempotent() throws Exception {
    sql("UPDATE channel_listing SET sku_id = ? WHERE id = ?", skuId, listingId);
    var ids = new java.util.ArrayList<String>();
    ids.add(listingId.toString());
    for (int i = 0; i < 3; i++) {
      UUID id = UuidV7.generate();
      ids.add(id.toString());
      sql(
          "INSERT INTO channel_listing (id, tenant_id, channel_account_id, external_sku_id, sku_id, stock_control) VALUES (?, ?, ?, ?, ?, false)",
          id,
          shop.tenantId(),
          accountId,
          "bulk-" + i,
          skuId);
    }
    var body =
        java.util.Map.of(
            "channel_account_id", accountId.toString(), "listing_ids", ids, "enabled", true);
    var first = http.post("/api/v1/channel-listings/stock-control", shop.owner(), body);
    assertThat(first.status()).as(first.raw()).isEqualTo(200);
    assertThat(first.body().path("changed").asInt()).isEqualTo(3);
    assertThat(audits()).isEqualTo(3);
    assertThat(
            http.post("/api/v1/channel-listings/stock-control", shop.owner(), body)
                .body()
                .path("changed")
                .asInt())
        .isZero();
    assertThat(audits()).isEqualTo(3);
    var page =
        http.get(
            "/api/v1/channel-listings?channel_account_id=" + accountId + "&stock_control=true",
            shop.owner());
    assertThat(page.body().path("total").asLong()).isEqualTo(4);
    assertThat(page.body().path("allowlist_coverage_pct").asDouble()).isEqualTo(100);
    ids.add(UuidV7.generate().toString());
    var rollback =
        java.util.Map.of(
            "channel_account_id", accountId.toString(), "listing_ids", ids, "enabled", false);
    assertThat(http.post("/api/v1/channel-listings/stock-control", shop.owner(), rollback).status())
        .isEqualTo(404);
    assertThat(audits()).isEqualTo(3);
    assertThat(
            http.get("/api/v1/channel-listings/" + listingId, shop.owner())
                .body()
                .path("stock_control")
                .asBoolean())
        .isTrue();
  }

  @Test
  void controlPushesOnlyEnabledListingsAndDisableNeverZeroes() throws Exception {
    sql("UPDATE channel_account SET mode = 'CONTROL' WHERE id = ?", accountId);
    sql("UPDATE channel_listing SET sku_id = ? WHERE id = ?", skuId, listingId);
    UUID other = UuidV7.generate();
    sql(
        "INSERT INTO channel_listing (id, tenant_id, channel_account_id, external_sku_id, sku_id, stock_control) VALUES (?, ?, ?, 'B', ?, false)",
        other,
        shop.tenantId(),
        accountId,
        skuId);
    com.thaishopfun.oms.tenant.TenantContext.set(shop.tenantId(), null);
    try {
      assertThat(pushes.flush(shop.tenantId(), accountId, true)).isEqualTo(1);
    } finally {
      com.thaishopfun.oms.tenant.TenantContext.clear();
    }
    try (var c = AuthTestSupport.admin();
        var ps =
            c.prepareStatement("SELECT last_pushed_version FROM channel_listing WHERE id = ?")) {
      ps.setObject(1, other);
      try (var rs = ps.executeQuery()) {
        rs.next();
        assertThat(rs.getObject(1)).isNull();
      }
    }
    assertThat(
            http.catalog()
                .put(
                    "/api/v1/channel-listings/" + other + "/stock-control",
                    shop.owner(),
                    java.util.Map.of("enabled", true))
                .status())
        .isEqualTo(200);
    com.thaishopfun.oms.tenant.TenantContext.set(shop.tenantId(), null);
    try {
      assertThat(pushes.flush(shop.tenantId(), accountId, true)).isEqualTo(1);
    } finally {
      com.thaishopfun.oms.tenant.TenantContext.clear();
    }
    assertThat(
            http.catalog()
                .put(
                    "/api/v1/channel-listings/" + other + "/stock-control",
                    shop.owner(),
                    java.util.Map.of("enabled", false))
                .status())
        .isEqualTo(200);
    com.thaishopfun.oms.tenant.TenantContext.set(shop.tenantId(), null);
    try {
      assertThat(pushes.flush(shop.tenantId(), accountId, true)).isZero();
    } finally {
      com.thaishopfun.oms.tenant.TenantContext.clear();
    }
  }

  @Test
  void permissionsAndControllability() throws Exception {
    var enabled = java.util.Map.of("enabled", true);
    assertThat(http.catalog().put(path(), http.catalog().member(shop, "STAFF"), enabled).status())
        .isEqualTo(403);
    assertThat(http.catalog().put(path(), http.catalog().shop().owner(), enabled).status())
        .isEqualTo(404);
    var unmapped = http.catalog().put(path(), shop.owner(), enabled);
    assertThat(unmapped.status()).isEqualTo(422);
    assertThat(unmapped.error()).isEqualTo("LISTING_NOT_CONTROLLABLE");
    sql("UPDATE channel_listing SET sku_id = ?, removed_at = now() WHERE id = ?", skuId, listingId);
    assertThat(http.catalog().put(path(), shop.owner(), enabled).status()).isEqualTo(422);
    assertThat(
            http.catalog().put(path(), shop.owner(), java.util.Map.of("enabled", false)).status())
        .isEqualTo(200);
    assertThat(audits()).isEqualTo(1);
    String grace =
        CatalogHttp.token(
            "grace-" + UUID.randomUUID(), "grace-shop-" + UUID.randomUUID(), "OWNER", "GRACE");
    var result = http.catalog().put(path(), grace, enabled);
    assertThat(result.status()).isEqualTo(403);
    assertThat(result.error()).isEqualTo("ENTITLEMENT_GRACE");
  }
}
