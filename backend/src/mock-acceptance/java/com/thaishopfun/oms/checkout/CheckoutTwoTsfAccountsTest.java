package com.thaishopfun.oms.checkout;

import static org.assertj.core.api.Assertions.assertThat;

import com.thaishopfun.oms.auth.AuthTestSupport;
import com.thaishopfun.oms.stock.StockFixture;
import com.thaishopfun.oms.tenant.TenantContext;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CheckoutTwoTsfAccountsTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final HttpClient HTTP =
      HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(5)).build();

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    AuthTestSupport.register(registry);
  }

  @LocalServerPort private int port;
  @Autowired JdbcTemplate jdbc;
  @Autowired PlatformTransactionManager transactions;
  @Autowired CheckoutRepository repository;

  StockFixture fixture;

  @BeforeEach
  void setup() {
    fixture = new StockFixture(jdbc, transactions);
  }

  @AfterEach
  void clear() {
    TenantContext.clear();
  }

  @Test
  void twoShopsOnOneTenantResolveAndEnforceIndependently() throws Exception {
    StockFixture.Shop shop = fixture.shop("ACTIVE");
    String activeShop = "tsf-active-" + shop.tenant();
    String shadowShop = "tsf-shadow-" + shop.tenant();
    UUID activeAccount = fixture.channelAccount(shop, activeShop, "ACTIVE", "CONNECTED");
    UUID shadowAccount = fixture.channelAccount(shop, shadowShop, "SHADOW", "CONNECTED");
    UUID skuActive = fixture.sku(shop, 10);
    UUID skuShadow = fixture.sku(shop, 8);
    fixture.channelListing(shop, activeAccount, "L-active", skuActive, true);
    fixture.channelListing(shop, shadowAccount, "L-shadow", skuShadow, true);

    // Step 1: resolve_tenant maps each external shop id to the same tenant.
    assertThat(repository.resolveTenant("TSF", activeShop)).isEqualTo(shop.tenant());
    assertThat(repository.resolveTenant("TSF", shadowShop)).isEqualTo(shop.tenant());

    HttpResponse<String> activeResponse =
        post("chk-active", request(activeShop, "chk-active", "L-active", 2));
    assertThat(activeResponse.statusCode()).isEqualTo(201);
    JsonNode activeBody = JSON.readTree(activeResponse.body());
    assertThat(activeBody.path("enforced").asBoolean()).isTrue();
    assertThat(fixture.reserved(shop, skuActive)).isEqualTo(2);

    HttpResponse<String> shadowResponse =
        post("chk-shadow", request(shadowShop, "chk-shadow", "L-shadow", 1));
    assertThat(shadowResponse.statusCode()).isEqualTo(201);
    JsonNode shadowBody = JSON.readTree(shadowResponse.body());
    assertThat(shadowBody.path("enforced").asBoolean()).isFalse();
    assertThat(fixture.reserved(shop, skuShadow)).isZero();
    long shadowDiffs =
        fixture.inTenant(
            shop.tenant(),
            () ->
                jdbc.queryForObject(
                    "SELECT count(*) FROM shadow_diff WHERE ref = ?", Long.class, "chk-shadow"));
    assertThat(shadowDiffs).isEqualTo(1);
  }

  @Test
  void observeModeDoesNotEnforceOrRecordShadowDiff() throws Exception {
    StockFixture.Shop shop = fixture.shop("ACTIVE");
    String observeShop = "tsf-observe-" + shop.tenant();
    UUID observeAccount = fixture.channelAccount(shop, observeShop, "OBSERVE", "CONNECTED");
    UUID sku = fixture.sku(shop, 5);
    fixture.channelListing(shop, observeAccount, "L-observe", sku, true);

    // Step 1: OBSERVE behaves like an unenforced reserve without shadow_diff rows.
    HttpResponse<String> response =
        post("chk-observe", request(observeShop, "chk-observe", "L-observe", 1));
    assertThat(response.statusCode()).isEqualTo(201);
    assertThat(JSON.readTree(response.body()).path("enforced").asBoolean()).isFalse();
    assertThat(fixture.reserved(shop, sku)).isZero();
    long diffs =
        fixture.inTenant(
            shop.tenant(),
            () ->
                jdbc.queryForObject(
                    "SELECT count(*) FROM shadow_diff WHERE ref = ?", Long.class, "chk-observe"));
    assertThat(diffs).isZero();
  }

  private HttpResponse<String> post(String idempotencyKey, ObjectNode body) throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(reserveUri())
            .header("Authorization", "Bearer " + serviceToken())
            .header("Content-Type", "application/json")
            .header("Idempotency-Key", idempotencyKey)
            .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
            .build();
    return HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  private URI reserveUri() {
    return URI.create("http://127.0.0.1:" + port + "/internal/v1/inventory/reservations");
  }

  private static String serviceToken() {
    return AuthTestSupport.token(
        "tsf-checkout",
        "shop",
        "ACTIVE",
        null,
        1,
        "oms-internal",
        Instant.now().plusSeconds(600),
        List.of(),
        "SERVICE");
  }

  private static ObjectNode request(String shopId, String checkoutId, String listingSku, int qty) {
    ObjectNode body = JSON.createObjectNode();
    body.put("checkout_id", checkoutId);
    body.put("tsf_shop_id", shopId);
    ArrayNode items = JSON.createArrayNode();
    ObjectNode item = JSON.createObjectNode();
    item.put("listing_sku_id", listingSku);
    item.put("qty", qty);
    items.add(item);
    body.set("items", items);
    return body;
  }
}
