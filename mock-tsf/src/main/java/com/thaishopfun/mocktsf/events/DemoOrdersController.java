package com.thaishopfun.mocktsf.events;

import com.thaishopfun.mocktsf.SeedData;
import com.thaishopfun.mocktsf.rest.TsfCatalog;
import com.thaishopfun.mocktsf.contract.ContractValidator;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Local/e2e demo orders for shop_active. Idempotent external ids (DEMO-*). Not part of the TSF
 * contract.
 */
@Profile("!prod")
@RestController
@RequestMapping("/control/demo")
public class DemoOrdersController {

  private static final String SHOP = "shop_active";
  private static final ContractValidator CONTRACT = ContractValidator.classpath();

  private final JsonMapper json;
  private final OmsCaller oms;
  private final SeedData shops;
  private final TsfCatalog catalog;

  public DemoOrdersController(JsonMapper json, OmsCaller oms, SeedData shops, TsfCatalog catalog) {
    this.json = json;
    this.oms = oms;
    this.shops = shops;
    this.catalog = catalog;
  }

  @PostMapping("/orders-seed")
  public ResponseEntity<Map<String, Object>> seed() throws IOException {
    if (shops.find(SHOP).isEmpty()) {
      throw new IllegalStateException("shop_active is not in the seed");
    }
    OmsCaller.CallResult catalog = oms.postDemoOrderCatalog();
    if (!catalog.reached() || catalog.status() >= 500) {
      // Step 1: Catalog setup is best-effort when OMS is not up yet (e2e stack still booting).
      // Events still flow; unmapped listings remain SKU_NOT_MAPPED until catalog succeeds.
    }
    List<Map<String, Object>> sent = new ArrayList<>();
    sent.add(send(created("DEMO-READY", "L-demo-ready", "PREPAID", 1)));
    sent.add(send(created("DEMO-COD", "L-demo-cod", "COD", 2)));
    sent.add(send(created("DEMO-OOS", "L-demo-oos", "COD", 3)));
    sent.add(send(created("DEMO-UNMAPPED", "L-demo-missing", "COD", 4)));
    sent.add(send(created("DEMO-BUNDLE", "L-demo-bundle", "COD", 5)));
    sent.add(send(paid("DEMO-READY", 6)));
    sent.add(send(created("DEMO-CANCELLED", "L-demo-cancel", "COD", 7)));
    sent.add(send(cancelled("DEMO-CANCELLED", 8)));
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("sent", sent.size());
    body.put("events", sent);
    return ResponseEntity.ok(body);
  }

  private Map<String, Object> send(ObjectNode event) throws IOException {
    String orderId = event.path("data").path("order_id").asString(null);
    if (orderId != null && orderId.startsWith("DEMO-")) {
      catalog.ensureDemoOrder(orderId);
    }
    byte[] raw = json.writeValueAsBytes(event);
    String eventId = event.path("event_id").asString();
    String signature = oms.signInbox(OmsCaller.now(), raw);
    OmsCaller.CallResult status = oms.postEvent(raw, eventId, signature);
    return Map.of("event_id", eventId, "status", status.status());
  }

  private ObjectNode created(String orderId, String listing, String payment, long version)
      throws IOException {
    ObjectNode event = load("order.created.json");
    event.put("event_id", "demo-" + orderId);
    event.put("tsf_shop_id", SHOP);
    event.put("aggregate_id", orderId);
    event.put("aggregate_version", version);
    event.put("occurred_at", Instant.now().truncatedTo(ChronoUnit.SECONDS).toString());
    ObjectNode data = (ObjectNode) event.get("data");
    data.put("order_id", orderId);
    data.put("reservation_id", "res-" + orderId);
    data.put("payment_method", payment);
    ArrayNode lines = json.createArrayNode();
    ObjectNode line = json.createObjectNode();
    line.put("line_id", "L1");
    line.put("listing_sku_id", listing);
    line.put("seller_sku", "SKU-DEMO");
    line.put("name", "Demo item");
    line.put("qty", 1);
    line.put("unit_price", 100);
    lines.add(line);
    data.set("lines", lines);
    assertValid(event);
    return event;
  }

  private ObjectNode paid(String orderId, long version) throws IOException {
    ObjectNode event = load("order.paid.json");
    event.put("event_id", "demo-paid-" + orderId);
    event.put("tsf_shop_id", SHOP);
    event.put("aggregate_id", orderId);
    event.put("aggregate_version", version);
    event.put("occurred_at", Instant.now().truncatedTo(ChronoUnit.SECONDS).toString());
    ((ObjectNode) event.get("data")).put("order_id", orderId);
    assertValid(event);
    return event;
  }

  private ObjectNode cancelled(String orderId, long version) throws IOException {
    ObjectNode event = load("order.cancelled.json");
    event.put("event_id", "demo-cancel-" + orderId);
    event.put("tsf_shop_id", SHOP);
    event.put("aggregate_id", orderId);
    event.put("aggregate_version", version);
    event.put("occurred_at", Instant.now().truncatedTo(ChronoUnit.SECONDS).toString());
    ((ObjectNode) event.get("data")).put("order_id", orderId);
    assertValid(event);
    return event;
  }

  private void assertValid(ObjectNode event) throws IOException {
    List<String> errors = CONTRACT.envelopeErrors(json.writeValueAsString(event));
    if (!errors.isEmpty()) {
      throw new IllegalStateException("invalid demo event: " + errors);
    }
  }

  private ObjectNode load(String name) throws IOException {
    try (InputStream in =
        DemoOrdersController.class.getResourceAsStream("/contracts/examples/events/" + name)) {
      if (in == null) {
        throw new IllegalStateException("missing example " + name);
      }
      return (ObjectNode) json.readTree(in);
    }
  }
}
