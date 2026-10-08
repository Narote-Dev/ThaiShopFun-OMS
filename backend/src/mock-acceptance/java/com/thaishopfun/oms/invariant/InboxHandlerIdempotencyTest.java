package com.thaishopfun.oms.invariant;

import static org.assertj.core.api.Assertions.assertThat;

import com.thaishopfun.mocktsf.MockTsfApplication;
import com.thaishopfun.oms.auth.AuthTestSupport;
import com.thaishopfun.oms.inbox.InboxHandler;
import com.thaishopfun.oms.inbox.InboxHandlerRegistry;
import com.thaishopfun.oms.inbox.InboxMessage;
import com.thaishopfun.oms.stock.StockFixture;
import com.thaishopfun.oms.tenant.TenantContext;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Each production {@link InboxHandler} must be safe to run twice with the same aggregate version.
 * Chaos-only handlers in {@code com.thaishopfun.oms.chaos} are excluded (not in {@link
 * InboxHandlerRegistry}).
 */
@ActiveProfiles("test")
@SpringBootTest(properties = "oms.inbox.worker-enabled=false")
@VerifyInvariants
@SkipInvariantCheck("Handler idempotency probes call handlers directly")
class InboxHandlerIdempotencyTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private static final Map<String, String> CONTRACTS =
      Map.of(
          "order.created", "order.created.json",
          "order.updated", "order.updated.json",
          "order.paid", "order.paid.json",
          "order.cancelled", "order.cancelled.json",
          "membership.changed", "membership.changed.json",
          "listing.changed", "listing.changed.json");

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    AuthTestSupport.register(registry);
  }

  @Autowired InboxHandlerRegistry registry;
  @Autowired JdbcTemplate jdbc;
  @Autowired PlatformTransactionManager transactions;

  StockFixture fixture;
  UUID tenantId;
  UUID channelAccountId;

  @BeforeEach
  void seed() {
    fixture = new StockFixture(jdbc, transactions);
    StockFixture.Shop shop = fixture.shop("ACTIVE");
    tenantId = shop.tenant();
    fixture.setTsfShopId(shop, "shop_45021");
    channelAccountId = fixture.channelAccount(shop, "shop_45021", "ACTIVE", "CONNECTED");
    fixture.channelListing(shop, channelAccountId, "tsf_sku_7781", fixture.sku(shop, 20), true);
  }

  @Test
  void everyRegisteredHandlerIsIdempotentOnDoubleDelivery() throws Exception {
    for (String eventType : CONTRACTS.keySet()) {
      InboxHandler handler = registry.find(eventType);
      assertThat(handler).as(eventType).isNotNull();
      TenantContext.set(tenantId, null);
      try {
        if (eventType.startsWith("order.") && !"order.created".equals(eventType)) {
          deliver(
              registry.find("order.created"),
              loadExample("order.created.json"),
              "seed-" + eventType);
        }
        JsonNode payload = loadExample(CONTRACTS.get(eventType));
        deliver(handler, payload, "idem-1");
        Map<String, Long> afterOne = fingerprint();
        deliver(handler, payload, "idem-2");
        deliver(handler, payload, "idem-2");
        assertThat(fingerprint()).isEqualTo(afterOne);
      } finally {
        TenantContext.clear();
      }
    }
  }

  private void deliver(InboxHandler handler, JsonNode root, String eventId) {
    InboxMessage message =
        new InboxMessage(
            UUID.randomUUID(),
            tenantId,
            "TSF",
            eventId,
            root.path("event_type").asString(),
            root.path("aggregate_id").asString(),
            root.path("aggregate_version").asLong(),
            false,
            root);
    fixture.runInTenant(tenantId, () -> handler.handle(message));
  }

  private Map<String, Long> fingerprint() {
    Map<String, Long> counts = new LinkedHashMap<>();
    counts.put("sales_order", jdbc.queryForObject("SELECT count(*) FROM sales_order", Long.class));
    counts.put(
        "stock_reservation",
        jdbc.queryForObject(
            "SELECT count(*) FROM stock_reservation WHERE status = 'ACTIVE'", Long.class));
    counts.put(
        "inventory_ledger",
        jdbc.queryForObject("SELECT count(*) FROM inventory_ledger", Long.class));
    counts.put(
        "channel_listing", jdbc.queryForObject("SELECT count(*) FROM channel_listing", Long.class));
    return counts;
  }

  private static JsonNode loadExample(String file) throws Exception {
    try (InputStream in =
        MockTsfApplication.class.getResourceAsStream("/contracts/examples/events/" + file)) {
      assertThat(in).as(file).isNotNull();
      return JSON.readTree(in);
    }
  }
}
