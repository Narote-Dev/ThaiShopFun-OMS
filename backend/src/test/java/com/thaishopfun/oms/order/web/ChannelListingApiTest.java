package com.thaishopfun.oms.order.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.thaishopfun.oms.auth.AuthTestSupport;
import com.thaishopfun.oms.auth.UuidV7;
import com.thaishopfun.oms.catalog.CatalogHttp;
import com.thaishopfun.oms.order.web.OrderIntegrationTest;
import java.sql.Connection;
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
    accountId =
        jdbc.queryForObject(
            "SELECT id FROM channel_account WHERE tenant_id = ? AND channel = 'TSF' LIMIT 1",
            UUID.class,
            shop.tenantId());
    UUID listing = UuidV7.generate();
    jdbc.update(
        """
        INSERT INTO channel_listing (
          id, tenant_id, channel_account_id, external_sku_id, seller_sku, name, stock_control
        ) VALUES (?, ?, ?, 'L-api', 'API-SKU', 'API listing', true)
        """,
        listing,
        shop.tenantId(),
        accountId);
  }

  @Test
  void listReturnsTenantListingsOverHttp() throws Exception {
    CatalogHttp.Result response =
        http.get(
            "/api/v1/channel-listings?channel_account_id=" + accountId + "&limit=50",
            shop.owner());
    assertThat(response.status()).isEqualTo(200);
    JsonNode body = response.body();
    assertThat(body.path("total").asInt()).isGreaterThanOrEqualTo(1);
    assertThat(body.path("items").size()).isGreaterThanOrEqualTo(1);
  }
}
