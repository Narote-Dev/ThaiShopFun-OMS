package com.thaishopfun.mocktsf.events;

import com.thaishopfun.mocktsf.contract.ContractValidator;
import com.thaishopfun.mocktsf.rest.TsfCatalog;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Harness endpoints to emit return.* inbox events toward OMS (not part of the TSF contract). */
@Profile({"local", "e2e", "test"})
@RestController
@RequestMapping("/control/returns")
public class ReturnEventsController {

  private static final ContractValidator CONTRACT = ContractValidator.classpath();

  private final JsonMapper json;
  private final OmsCaller oms;
  private final WebhookDeliveryGate webhooks;
  private final TsfCatalog catalog;

  public ReturnEventsController(
      JsonMapper json, OmsCaller oms, WebhookDeliveryGate webhooks, TsfCatalog catalog) {
    this.json = json;
    this.oms = oms;
    this.webhooks = webhooks;
    this.catalog = catalog;
  }

  @PostMapping("/{orderId}/request")
  public ResponseEntity<String> request(@PathVariable String orderId, HttpServletRequest request)
      throws IOException {
    JsonNode body = readObject(request);
    String returnId = text(body, "return_id");
    String shopId = body.path("shop_id").asString("shop_active");
    ObjectNode event = load("return.requested.json");
    event.put("event_id", "mock-ret-req-" + returnId);
    event.put("tsf_shop_id", shopId);
    event.put("aggregate_id", returnId);
    event.put("aggregate_version", body.path("aggregate_version").asLong(7));
    event.put("occurred_at", Instant.now().truncatedTo(ChronoUnit.SECONDS).toString());
    ObjectNode data = (ObjectNode) event.get("data");
    data.put("order_id", orderId);
    data.put("return_id", returnId);
    if (body.has("type")) {
      data.put("type", body.path("type").asString());
    }
    if (body.has("reason")) {
      data.put("reason", body.path("reason").asString());
    }
    if (body.has("lines") && body.get("lines").isArray()) {
      data.set("lines", body.get("lines"));
    }
    assertValid(event);
    catalog.ensureDemoOrder(orderId);
    return ResponseEntity.ok().body(json.writeValueAsString(deliver(event)));
  }

  @PostMapping("/{orderId}/decide")
  public ResponseEntity<String> decide(@PathVariable String orderId, HttpServletRequest request)
      throws IOException {
    JsonNode body = readObject(request);
    String returnId = text(body, "return_id");
    String decision = text(body, "decision");
    String shopId = body.path("shop_id").asString("shop_active");
    ObjectNode event = load("return.decided.json");
    event.put("event_id", "mock-ret-dec-" + returnId + "-" + decision);
    event.put("tsf_shop_id", shopId);
    event.put("aggregate_id", returnId);
    event.put("aggregate_version", body.path("aggregate_version").asLong(8));
    event.put("occurred_at", Instant.now().truncatedTo(ChronoUnit.SECONDS).toString());
    ObjectNode data = (ObjectNode) event.get("data");
    data.put("order_id", orderId);
    data.put("return_id", returnId);
    data.put("decision", decision);
    assertValid(event);
    return ResponseEntity.ok().body(json.writeValueAsString(deliver(event)));
  }

  private Map<String, Object> deliver(ObjectNode event) throws IOException {
    if (!webhooks.enabled()) {
      catalog.noteOrderEvent(event);
      return Map.of(
          "event_id",
          event.path("event_id").asString(),
          "http_status",
          0,
          "body",
          "webhooks_disabled");
    }
    byte[] raw = json.writeValueAsBytes(event);
    String eventId = event.path("event_id").asString();
    String signature = oms.signInbox(OmsCaller.now(), raw);
    OmsCaller.CallResult result = oms.postEvent(raw, eventId, signature);
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("event_id", eventId);
    row.put("http_status", result.status());
    row.put("body", result.body());
    return row;
  }

  private void assertValid(ObjectNode event) throws IOException {
    List<String> errors = CONTRACT.envelopeErrors(json.writeValueAsString(event));
    if (!errors.isEmpty()) {
      throw new IllegalStateException("invalid return event: " + errors);
    }
  }

  private ObjectNode load(String name) throws IOException {
    try (var in =
        ReturnEventsController.class.getResourceAsStream("/contracts/examples/events/" + name)) {
      if (in == null) {
        throw new IllegalStateException("missing example " + name);
      }
      return (ObjectNode) json.readTree(in);
    }
  }

  private JsonNode readObject(HttpServletRequest request) throws IOException {
    JsonNode node = json.readTree(readRaw(request));
    if (node == null || !node.isObject()) {
      throw new com.thaishopfun.mocktsf.ApiException(
          400, "BAD_REQUEST", "body must be a JSON object");
    }
    return node;
  }

  private static String readRaw(HttpServletRequest request) throws IOException {
    byte[] raw = request.getInputStream().readNBytes(1024 * 1024 + 1);
    if (raw.length == 0 || raw.length > 1024 * 1024) {
      throw new com.thaishopfun.mocktsf.ApiException(400, "BAD_REQUEST", "JSON body is required");
    }
    return new String(raw, StandardCharsets.UTF_8);
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || !value.isString() || value.asString().isBlank()) {
      throw new com.thaishopfun.mocktsf.ApiException(400, "BAD_REQUEST", field + " is required");
    }
    return value.asString();
  }
}
