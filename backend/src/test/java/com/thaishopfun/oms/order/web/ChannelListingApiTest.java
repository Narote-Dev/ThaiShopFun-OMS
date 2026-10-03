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
import tools.jackson.databind.JsonNode;

/** Listing list must return rows under RLS via tenant-scoped read transactions. */
class ChannelListingApiTest extends OrderIntegrationTest {

  @Autowired JdbcTemplate jdbc;

  CatalogHttp.Shop shop;
  UUID accountId;

  @BeforeEach
  void seed() throws Exception {
    try (Connection admin = AuthTestSupport.admin();
        var statement = admin.createStatement()) {
      statement.execute("SET session_replication_role = replica");
      statement.execute("TRUNCATE TABLE channel_listing CASCADE");
      statement.execute("SET session_replication_role = DEFAULT");
    }
    shop = http.catalog().shop();
    accountId = OrderFixture.shopFor(shop).channelAccountId();
    UUID listing = UuidV7.generate();
    try (Connection admin = AuthTestSupport.admin();
        PreparedStatement statement =
            admin.prepareStatement(
                """
                INSERT INTO channel_listing (
                  id, tenant_id, channel_account_id, external_sku_id, seller_sku, name, stock_control
                ) VALUES (?, ?, ?, 'L-api', 'API-SKU', 'API listing', true)
                """)) {
      statement.setObject(1, listing);
      statement.setObject(2, shop.tenantId());
      statement.setObject(3, accountId);
      statement.executeUpdate();
    }
  }

  @Test
  void staffCannotPutDeleteMappingOrSync() throws Exception {
    UUID listing =
        jdbc.queryForObject(
            "SELECT id FROM channel_listing WHERE external_sku_id = 'L-api'",
            UUID.class);
    UUID sku =
        jdbc.queryForObject(
            "SELECT id FROM sku WHERE tenant_id = ? LIMIT 1", UUID.class, shop.tenantId());
    String staff = http.catalog().member(shop, "STAFF");
    assertThat(
            http.catalog()
                .put(
                    "/api/v1/channel-listings/" + listing + "/mapping",
                    staff,
                    java.util.Map.of("sku_id", sku.toString()))
                .status())
        .isEqualTo(403);
    assertThat(
            http.catalog().delete("/api/v1/channel-listings/" + listing + "/mapping", staff).status())
        .isEqualTo(403);
    assertThat(
            http.catalog()
                .post("/api/v1/channel-accounts/" + accountId + "/listing-syncs", staff, null)
                .status())
        .isEqualTo(403);
  }

  @Test
  void listReturnsTenantListingsOverHttp() throws Exception {
    CatalogHttp.Result response =
        http.get(
            "/api/v1/channel-listings?channel_account_id=" + accountId + "&limit=50", shop.owner());
    assertThat(response.status()).isEqualTo(200);
    JsonNode body = response.body();
    assertThat(body.path("total").asInt()).isGreaterThanOrEqualTo(1);
    assertThat(body.path("items").size()).isGreaterThanOrEqualTo(1);
  }
}
