package com.thaishopfun.oms.checkout;

import static org.assertj.core.api.Assertions.assertThat;

import com.thaishopfun.mocktsf.contract.ContractValidator;
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
class CheckoutApiTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final HttpClient HTTP =
      HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(5)).build();
  private static final ContractValidator CONTRACT = ContractValidator.classpath();

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    AuthTestSupport.register(registry);
  }

  @LocalServerPort private int port;
  @Autowired JdbcTemplate jdbc;
  @Autowired PlatformTransactionManager transactions;

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
  void activeModeReservesAndValidatesContract() throws Exception {
    StockFixture.Shop shop = fixture.shop("ACTIVE");
    String shopId = fixture.tsfShopId(shop);
    UUID account = fixture.channelAccount(shop, "ACTIVE", "CONNECTED");
    UUID sku = fixture.sku(shop, 10);
    fixture.channelListing(shop, account, "L-1", sku, true);
    ObjectNode body = request(shopId, "chk-1", "L-1", 2);
    HttpResponse<String> response = post("chk-1", body);
    assertThat(response.statusCode()).isEqualTo(201);
    assertThat(CONTRACT.restErrors("reservation-created", response.body())).isEmpty();
    JsonNode created = JSON.readTree(response.body());
    assertThat(created.path("enforced").asBoolean()).isTrue();
    assertThat(fixture.reserved(shop, sku)).isEqualTo(2);
  }

  @Test
  void removedMappedListingIsNotEnforced() throws Exception {
    StockFixture.Shop shop = fixture.shop("ACTIVE");
    String shopId = fixture.tsfShopId(shop);
    UUID account = fixture.channelAccount(shop, "ACTIVE", "CONNECTED");
    UUID sku = fixture.sku(shop, 10);
    fixture.channelListing(shop, account, "L-removed", sku, true);
    fixture.inTenant(
        shop.tenant(),
        () ->
            jdbc.update(
                "UPDATE channel_listing SET removed_at = now() WHERE channel_account_id = ? AND external_sku_id = ?",
                account,
                "L-removed"));
    HttpResponse<String> response =
        post("chk-removed", request(shopId, "chk-removed", "L-removed", 1));
    assertThat(response.statusCode()).isEqualTo(201);
    JsonNode created = JSON.readTree(response.body());
    assertThat(created.path("enforced").asBoolean()).isFalse();
    assertThat(fixture.reserved(shop, sku)).isZero();
    long reservations =
        fixture.inTenant(
            shop.tenant(),
            () -> jdbc.queryForObject("SELECT count(*) FROM stock_reservation", Long.class));
    assertThat(reservations).isZero();
  }

  @Test
  void shadowModeRecordsShadowDiff() throws Exception {
    StockFixture.Shop shop = fixture.shop("ACTIVE");
    String shopId = fixture.tsfShopId(shop);
    UUID account = fixture.channelAccount(shop, "SHADOW", "CONNECTED");
    UUID sku = fixture.sku(shop, 5);
    fixture.channelListing(shop, account, "L-shadow", sku, true);
    HttpResponse<String> response =
        post("chk-shadow", request(shopId, "chk-shadow", "L-shadow", 1));
    assertThat(response.statusCode()).isEqualTo(201);
    JsonNode created = JSON.readTree(response.body());
    assertThat(created.path("enforced").asBoolean()).isFalse();
    long diffs =
        fixture.inTenant(
            shop.tenant(),
            () ->
                jdbc.queryForObject(
                    "SELECT count(*) FROM shadow_diff WHERE ref = ?", Long.class, "chk-shadow"));
    assertThat(diffs).isEqualTo(1);
  }

  @Test
  void idempotencyConflictOnDifferentBody() throws Exception {
    StockFixture.Shop shop = fixture.shop("ACTIVE");
    String shopId = fixture.tsfShopId(shop);
    UUID account = fixture.channelAccount(shop, "ACTIVE", "CONNECTED");
    UUID sku = fixture.sku(shop, 10);
    fixture.channelListing(shop, account, "L-2", sku, true);
    post("chk-2", request(shopId, "chk-2", "L-2", 1));
    HttpResponse<String> second = post("chk-2", request(shopId, "chk-2", "L-2", 2));
    assertThat(second.statusCode()).isEqualTo(409);
    assertThat(CONTRACT.restErrors("error", second.body())).isEmpty();
    assertThat(JSON.readTree(second.body()).path("error").asString())
        .isEqualTo("IDEMPOTENCY_CONFLICT");
  }

  @Test
  void deleteIsAlways204() throws Exception {
    assertThat(delete("not-a-uuid").statusCode()).isEqualTo(204);
    assertThat(delete(UUID.randomUUID().toString()).statusCode()).isEqualTo(204);
  }

  @Test
  void mismatchedIdempotencyKeyIs400() throws Exception {
    StockFixture.Shop shop = fixture.shop("ACTIVE");
    ObjectNode body = request(fixture.tsfShopId(shop), "chk-bad", "L-x", 1);
    HttpResponse<String> response = post("other-key", body);
    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(CONTRACT.restErrors("error", response.body())).isEmpty();
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

  private HttpResponse<String> delete(String reservationId) throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(
                URI.create(
                    "http://127.0.0.1:"
                        + port
                        + "/internal/v1/inventory/reservations/"
                        + reservationId))
            .header("Authorization", "Bearer " + serviceToken())
            .DELETE()
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
