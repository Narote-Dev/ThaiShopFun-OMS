package com.thaishopfun.mocktsf.events;

import com.thaishopfun.mocktsf.ApiException;
import com.thaishopfun.mocktsf.Hmacs;
import com.thaishopfun.mocktsf.MockProperties;
import com.thaishopfun.mocktsf.SeedData;
import com.thaishopfun.mocktsf.contract.ContractResponses;
import com.thaishopfun.mocktsf.idp.TokenIssuer;
import com.thaishopfun.mocktsf.rest.TsfCatalog;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Local harness. Sends contract events to OMS and reads what OMS published back. Not part of the
 * TSF contract; the events it carries are.
 */
@RestController
@RequestMapping("/control")
public class ControlController {

  private final JsonMapper json;
  private final ContractResponses responses;
  private final OmsCaller oms;
  private final MockProperties properties;
  private final SeedData shops;
  private final TokenIssuer tokens;
  private final ReceivedEventStore received;
  private final FaultSchedule faults;
  private final WebhookDeliveryGate webhooks;
  private final TsfCatalog catalog;

  public ControlController(
      JsonMapper json,
      ContractResponses responses,
      OmsCaller oms,
      MockProperties properties,
      SeedData shops,
      TokenIssuer tokens,
      ReceivedEventStore received,
      FaultSchedule faults,
      WebhookDeliveryGate webhooks,
      TsfCatalog catalog) {
    this.json = json;
    this.responses = responses;
    this.oms = oms;
    this.properties = properties;
    this.shops = shops;
    this.tokens = tokens;
    this.received = received;
    this.faults = faults;
    this.webhooks = webhooks;
    this.catalog = catalog;
  }

  @PostMapping("/listings/{listingSkuId}/hidden")
  public ResponseEntity<String> listingHidden(
      @PathVariable String listingSkuId, HttpServletRequest request) throws IOException {
    JsonNode body = readObject(request);
    boolean hidden = body.path("hidden").asBoolean(false);
    catalog.setListingHidden(listingSkuId, hidden);
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("listing_sku_id", listingSkuId);
    result.put("hidden", hidden);
    return responses.outbound(200, "listing-hidden", result);
  }

  @PostMapping("/orders/register")
  public ResponseEntity<String> registerOrder(HttpServletRequest request) throws IOException {
    JsonNode body = readObject(request);
    String shopId = text(body, "shop_id");
    String orderId = text(body, "order_id");
    catalog.registerOrder(shopId, orderId);
    return ResponseEntity.ok()
        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
        .body(json.writeValueAsString(Map.of("order_id", orderId)));
  }

  @PostMapping("/orders/{orderId}/mark-paid")
  public ResponseEntity<String> markPaid(@PathVariable String orderId, HttpServletRequest request)
      throws IOException {
    JsonNode body = readObject(request);
    long version = body.path("aggregate_version").asLong(2);
    catalog.markPaid(orderId, version);
    return ResponseEntity.ok()
        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
        .body(json.writeValueAsString(Map.of("order_id", orderId, "aggregate_version", version)));
  }

  @PostMapping("/catalog/note-event")
  public ResponseEntity<String> noteCatalogEvent(HttpServletRequest request) throws IOException {
    JsonNode body = readObject(request);
    JsonNode event = body.get("event");
    if (event == null || !event.isObject()) {
      throw ApiException.schema("event object is required");
    }
    catalog.noteOrderEvent(event);
    return ResponseEntity.ok()
        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
        .body(json.writeValueAsString(Map.of("ok", true)));
  }

  @PostMapping("/webhooks")
  public ResponseEntity<String> webhooks(HttpServletRequest request) throws IOException {
    JsonNode body = readObject(request);
    boolean enabled = body.path("enabled").asBoolean(true);
    webhooks.setEnabled(enabled);
    return ResponseEntity.ok()
        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
        .body(json.writeValueAsString(Map.of("enabled", enabled)));
  }

  @PostMapping("/orders/bulk")
  public ResponseEntity<String> bulkOrders(HttpServletRequest request) throws IOException {
    JsonNode body = readObject(request);
    int count = body.path("count").asInt(0);
    if (count < 1 || count > 200) {
      throw ApiException.badRequest("BAD_REQUEST", "count must be between 1 and 200");
    }
    String payment = text(body, "payment");
    if (!payment.equals("PREPAID") && !payment.equals("COD")) {
      throw ApiException.badRequest("BAD_REQUEST", "payment must be PREPAID or COD");
    }
    boolean paid = body.path("paid").asBoolean(false);
    String shopId = body.has("shop_id") ? text(body, "shop_id") : "shop_active";
    List<String> created = catalog.createBulkOrders(shopId, count, payment, paid);
    List<Map<String, Object>> delivered = new ArrayList<>();
    if (webhooks.enabled()) {
      for (String orderId : created) {
        delivered.add(deliverBulkCreated(shopId, orderId, payment, paid));
        if (paid && "PREPAID".equals(payment)) {
          delivered.add(deliverBulkPaid(shopId, orderId));
        }
      }
    }
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("created", created.size());
    result.put("order_ids", created);
    result.put("webhooks_enabled", webhooks.enabled());
    result.put("delivered", delivered);
    return ResponseEntity.ok()
        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
        .body(json.writeValueAsString(result));
  }

  @PostMapping("/user-token")
  public ResponseEntity<String> userToken(HttpServletRequest request) throws IOException {
    JsonNode body = readObject(request);
    String hint = text(body, "login_hint");
    SeedData.ShopUser user =
        shops
            .find(hint)
            .orElseThrow(() -> ApiException.badRequest("UNKNOWN_USER", "login_hint is unknown"));
    // Step 1: Overrides apply to this token only. The seed stays until a membership event lands.
    user =
        shops.override(user, longOrNull(body, "ent_ver"), statusOrNull(body), instantOrNull(body));
    Map<String, Object> token = new LinkedHashMap<>();
    token.put("access_token", tokens.userAccessToken(user));
    token.put("token_type", "Bearer");
    token.put("expires_in", Math.toIntExact(properties.getAccessTokenSeconds()));
    return responses.outbound(200, "token-response", token);
  }

  @PostMapping("/events/send")
  public ResponseEntity<String> send(HttpServletRequest request) throws IOException {
    return report(List.of(deliver(eventOf(readObject(request)), OmsCaller.now(), true)));
  }

  @PostMapping("/listing-changed")
  public ResponseEntity<String> listingChanged(HttpServletRequest request) throws IOException {
    JsonNode body = readObject(request);
    String shopId = text(body, "tsf_shop_id");
    String listingSkuId = text(body, "listing_sku_id");
    String action = text(body, "action");
    long version = body.path("aggregate_version").asLong(1);
    ObjectNode event = json.createObjectNode();
    event.put("event_id", "evt-listing-" + java.util.UUID.randomUUID());
    event.put("event_type", "listing.changed");
    event.put("schema_version", 1);
    event.put("occurred_at", Instant.now().toString());
    event.put("tsf_shop_id", shopId);
    event.put("aggregate_id", listingSkuId);
    event.put("aggregate_version", version);
    ObjectNode data = json.createObjectNode();
    data.put("listing_sku_id", listingSkuId);
    data.put("action", action);
    if (body.has("seller_sku")) {
      data.put("seller_sku", body.path("seller_sku").asString());
    }
    if (body.has("name")) {
      data.put("name", body.path("name").asString());
    }
    event.set("data", data);
    return report(List.of(deliver(event, OmsCaller.now(), true)));
  }

  @PostMapping("/events/repeat")
  public ResponseEntity<String> repeat(HttpServletRequest request) throws IOException {
    // Step 1: One payload, N deliveries, same event_id. OMS must treat the rest as duplicates.
    JsonNode body = readObject(request);
    int times = body.path("times").asInt(0);
    if (times < 1 || times > 20) {
      throw ApiException.badRequest("BAD_REQUEST", "times must be between 1 and 20");
    }
    byte[] raw = canonical(eventOf(body));
    String eventId = eventId(raw);
    String signature = oms.signInbox(OmsCaller.now(), raw);
    List<Map<String, Object>> sent = new ArrayList<>();
    for (int i = 0; i < times; i++) {
      sent.add(row(eventId, raw, oms.postEvent(raw, eventId, signature)));
    }
    return report(sent);
  }

  @PostMapping("/events/shuffle")
  public ResponseEntity<String> shuffle(HttpServletRequest request) throws IOException {
    // Step 1: Validate every event, then send them in an order that is not the input order.
    JsonNode body = readObject(request);
    JsonNode events = body.get("events");
    if (events == null || !events.isArray() || events.size() < 2) {
      throw ApiException.badRequest("BAD_REQUEST", "events must contain at least two envelopes");
    }
    List<byte[]> raws = new ArrayList<>();
    List<String> ids = new ArrayList<>();
    for (JsonNode event : events) {
      if (!event.isObject()) {
        throw ApiException.schema("each event must be an object");
      }
      byte[] raw = canonical((ObjectNode) event);
      raws.add(raw);
      ids.add(eventId(raw));
    }
    List<Integer> order = new ArrayList<>();
    for (int i = 0; i < raws.size(); i++) {
      order.add(i);
    }
    Collections.shuffle(order);
    boolean same = true;
    for (int i = 0; i < order.size(); i++) {
      same &= order.get(i) == i;
    }
    if (same) {
      Collections.swap(order, 0, 1);
    }
    long now = OmsCaller.now();
    List<Map<String, Object>> sent = new ArrayList<>();
    for (int index : order) {
      byte[] raw = raws.get(index);
      sent.add(
          row(ids.get(index), raw, oms.postEvent(raw, ids.get(index), oms.signInbox(now, raw))));
    }
    return report(sent);
  }

  @PostMapping("/events/stale")
  public ResponseEntity<String> stale(HttpServletRequest request) throws IOException {
    JsonNode body = readObject(request);
    long skew = body.path("skew_seconds").asLong(Hmacs.MAX_SKEW_SECONDS + 1);
    if (skew <= Hmacs.MAX_SKEW_SECONDS) {
      throw ApiException.badRequest("BAD_REQUEST", "skew_seconds must be greater than 300");
    }
    byte[] raw = canonical(eventOf(body));
    long timestamp = OmsCaller.now() - skew;
    return report(
        List.of(
            row(
                eventId(raw),
                raw,
                oms.postEvent(raw, eventId(raw), oms.signInbox(timestamp, raw)))));
  }

  @PostMapping("/events/bad-signature")
  public ResponseEntity<String> badSignature(HttpServletRequest request) throws IOException {
    byte[] raw = canonical(eventOf(readObject(request)));
    String signature = Hmacs.header("invalid-signature-secret", OmsCaller.now(), raw);
    return report(List.of(row(eventId(raw), raw, oms.postEvent(raw, eventId(raw), signature))));
  }

  @PostMapping("/events/after-reservation-expiry")
  public ResponseEntity<String> afterExpiry(HttpServletRequest request) throws IOException {
    // Step 1: Only order.created. Stamp occurred_at one second after expiry when it is not already
    // later, and refuse a stamp that would be in the future.
    JsonNode body = readObject(request);
    ObjectNode event = eventOf(body);
    if (!"order.created".equals(event.path("event_type").asString())) {
      throw ApiException.badRequest(
          "BAD_REQUEST", "after-reservation-expiry applies only to order.created");
    }
    String expiryText = text(body, "reservation_expires_at");
    Instant expiry;
    try {
      expiry = Instant.parse(expiryText);
    } catch (Exception ex) {
      throw ApiException.badRequest("BAD_REQUEST", "reservation_expires_at is invalid");
    }
    Instant occurred;
    try {
      occurred =
          event.hasNonNull("occurred_at")
              ? Instant.parse(event.get("occurred_at").asString())
              : Instant.EPOCH;
    } catch (Exception ex) {
      throw ApiException.badRequest("BAD_REQUEST", "occurred_at is invalid");
    }
    Instant stamped = occurred.isAfter(expiry) ? occurred : expiry.plusSeconds(1);
    if (stamped.isAfter(Instant.now())) {
      throw ApiException.badRequest("BAD_REQUEST", "occurred_at would be in the future");
    }
    event.put("occurred_at", stamped.toString());
    return report(List.of(deliver(event, OmsCaller.now(), true)));
  }

  @PostMapping("/faults")
  public ResponseEntity<String> faults(HttpServletRequest request) throws IOException {
    // Step 1: Arm the next N authenticated calls to one 4.7 path. 429 requires Retry-After.
    JsonNode body = readObject(request);
    String method = text(body, "method").toUpperCase(java.util.Locale.ROOT);
    String path = text(body, "path");
    if (!path.startsWith("/internal/") || path.startsWith("/internal/v1/oms-events")) {
      throw ApiException.badRequest("BAD_REQUEST", "path must be a section 4.7 route");
    }
    int status = body.path("status").asInt(0);
    if (status != 429 && status != 503) {
      throw ApiException.badRequest("BAD_REQUEST", "status must be 429 or 503");
    }
    int times = body.path("times").asInt(0);
    if (times < 1 || times > 20) {
      throw ApiException.badRequest("BAD_REQUEST", "times must be between 1 and 20");
    }
    int skip = body.path("skip").asInt(0);
    if (skip < 0 || skip > 50) {
      throw ApiException.badRequest("BAD_REQUEST", "skip must be between 0 and 50");
    }
    Integer retryAfter = null;
    if (status == 429) {
      if (!body.path("retry_after").isIntegralNumber() || body.path("retry_after").asInt() < 1) {
        throw ApiException.badRequest("BAD_REQUEST", "retry_after is required for 429");
      }
      retryAfter = body.path("retry_after").asInt();
    }
    faults.arm(method, path, status, times, retryAfter, skip);
    Map<String, Object> armed = new LinkedHashMap<>();
    armed.put("method", method);
    armed.put("path", path);
    armed.put("status", status);
    armed.put("times", times);
    if (skip > 0) {
      armed.put("skip", skip);
    }
    if (retryAfter != null) {
      armed.put("retry_after", retryAfter);
    }
    return ResponseEntity.ok()
        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
        .body(json.writeValueAsString(armed));
  }

  @PostMapping("/checkout/reservations")
  public ResponseEntity<String> reserve(HttpServletRequest request) throws IOException {
    String raw = readRaw(request);
    responses.requireInboundRest("reservation-request", raw);
    JsonNode body = json.readTree(raw);
    String checkoutId = body.path("checkout_id").asString();
    OmsCaller.CallResult result =
        oms.postReservation(raw.getBytes(StandardCharsets.UTF_8), checkoutId);
    return responses.outbound(200, "checkout-result", checkoutResult(result, true));
  }

  @DeleteMapping("/checkout/reservations/{reservationId}")
  public ResponseEntity<String> release(@PathVariable String reservationId) {
    if (reservationId.isBlank()) {
      throw ApiException.badRequest("BAD_REQUEST", "reservation id is required");
    }
    return responses.outbound(
        200, "checkout-result", checkoutResult(oms.deleteReservation(reservationId), false));
  }

  @GetMapping("/received-events")
  public ResponseEntity<String> received() {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("events", received.view());
    return responses.outbound(200, "received-events", body);
  }

  private Map<String, Object> deliverBulkCreated(
      String shopId, String orderId, String payment, boolean paid) {
    ObjectNode event = json.createObjectNode();
    event.put("event_id", "bulk-created-" + orderId);
    event.put("event_type", "order.created");
    event.put("schema_version", 1);
    event.put("occurred_at", Instant.now().toString());
    event.put("tsf_shop_id", shopId);
    event.put("aggregate_id", orderId);
    event.put("aggregate_version", 1);
    ObjectNode data = json.createObjectNode();
    data.put("order_id", orderId);
    data.put("reservation_id", java.util.UUID.randomUUID().toString());
    data.put("payment_method", payment);
    if ("PREPAID".equals(payment)) {
      data.put("payment_expires_at", Instant.now().plusSeconds(3600).toString());
    }
    data.put("currency", "THB");
    ObjectNode totals = json.createObjectNode();
    totals.put("subtotal", 100);
    totals.put("shipping_fee", 0);
    totals.put("discount", 0);
    totals.put("grand_total", 100);
    data.set("totals", totals);
    ObjectNode recipient = json.createObjectNode();
    recipient.put("name", "Bulk Buyer");
    recipient.put("phone", "0890000000");
    ObjectNode address = json.createObjectNode();
    address.put("line1", "1 Test Road");
    address.put("district", "Test");
    address.put("province", "Bangkok");
    address.put("postcode", "10110");
    recipient.set("address", address);
    data.set("recipient", recipient);
    data.put("ship_by", Instant.now().plusSeconds(86400).toString());
    ObjectNode line = json.createObjectNode();
    line.put("line_id", "L1");
    line.put("listing_sku_id", "tsf_sku_7781");
    line.put("seller_sku", "TSHIRT-BLK-M");
    line.put("name", "เสื้อยืดดำ M");
    line.put("qty", 1);
    line.put("unit_price", 100);
    data.set("lines", json.createArrayNode().add(line));
    event.set("data", data);
    return deliver(event, OmsCaller.now(), true);
  }

  private Map<String, Object> deliverBulkPaid(String shopId, String orderId) {
    ObjectNode event = json.createObjectNode();
    event.put("event_id", "bulk-paid-" + orderId);
    event.put("event_type", "order.paid");
    event.put("schema_version", 1);
    event.put("occurred_at", Instant.now().toString());
    event.put("tsf_shop_id", shopId);
    event.put("aggregate_id", orderId);
    event.put("aggregate_version", 2);
    ObjectNode data = json.createObjectNode();
    data.put("order_id", orderId);
    event.set("data", data);
    return deliver(event, OmsCaller.now(), true);
  }

  private Map<String, Object> deliver(ObjectNode event, long timestamp, boolean validSignature) {
    if (!webhooks.enabled()) {
      catalog.noteOrderEvent(event);
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("event_id", event.path("event_id").asString());
      row.put("http_status", 0);
      row.put("body", "webhooks_disabled");
      return row;
    }
    byte[] raw = canonical(event);
    String eventId = eventId(raw);
    String signature =
        validSignature
            ? oms.signInbox(timestamp, raw)
            : Hmacs.header("invalid-signature-secret", timestamp, raw);
    return row(eventId, raw, oms.postEvent(raw, eventId, signature));
  }

  private byte[] canonical(ObjectNode event) {
    String payload = json.writeValueAsString(event);
    responses.requireInboundEnvelope(payload);
    return payload.getBytes(StandardCharsets.UTF_8);
  }

  private String eventId(byte[] raw) {
    return json.readTree(raw).path("event_id").asString();
  }

  private ObjectNode eventOf(JsonNode body) {
    JsonNode event = body.get("event");
    if (event == null || !event.isObject()) {
      throw ApiException.schema("event object is required");
    }
    return (ObjectNode) event;
  }

  private ResponseEntity<String> report(List<Map<String, Object>> sent) {
    boolean down = false;
    for (Map<String, Object> row : sent) {
      down |= Integer.valueOf(0).equals(row.get("http_status"));
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("sent", sent);
    return responses.outbound(down ? 502 : 200, "delivery-report", body);
  }

  private Map<String, Object> row(String eventId, byte[] raw, OmsCaller.CallResult result) {
    // Step 1: Only the first accept moves the seed. A 200 duplicate must not rewrite it.
    if (result.status() == 202) {
      shops.noteAccepted(json.readTree(raw));
    }
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("event_id", eventId);
    row.put("http_status", result.status());
    row.put("body", result.body());
    return row;
  }

  private Map<String, Object> checkoutResult(OmsCaller.CallResult result, boolean reserve) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("oms_status", result.status());
    body.put("oms_body", snippet(result.body()));
    body.put("response_schema_valid", schemaOk(result, reserve));
    return body;
  }

  private boolean schemaOk(OmsCaller.CallResult result, boolean reserve) {
    if (!result.reached()) {
      return false;
    }
    String body = result.body() == null ? "" : result.body();
    try {
      // Step 1: Reserve is 201, 409, or an error. Release is 204 or an error. Anything else is
      // invalid. A non-JSON body is invalid and must not fail the checkout report itself.
      if (reserve && result.status() == 201) {
        return responses.validator().restErrors("reservation-created", body).isEmpty();
      }
      if (reserve && result.status() == 409) {
        return responses.validator().restErrors("reservation-conflict", body).isEmpty()
            || responses.validator().restErrors("error", body).isEmpty();
      }
      if (!reserve && result.status() == 204) {
        return body.isBlank();
      }
      if (result.status() >= 400) {
        return responses.validator().restErrors("error", body).isEmpty();
      }
      return false;
    } catch (RuntimeException ex) {
      return false;
    }
  }

  private static String snippet(String raw) {
    if (raw == null || raw.isEmpty()) {
      return "";
    }
    return raw.length() <= 240 ? raw : raw.substring(0, 240);
  }

  private static Long longOrNull(JsonNode body, String field) {
    JsonNode value = body.get(field);
    if (value == null || value.isNull()) {
      return null;
    }
    if (!value.isIntegralNumber()) {
      throw ApiException.badRequest("BAD_REQUEST", field + " is invalid");
    }
    BigInteger number = value.bigIntegerValue();
    if (number.signum() < 0 || number.bitLength() > 63) {
      throw ApiException.badRequest("BAD_REQUEST", field + " is invalid");
    }
    return number.longValue();
  }

  private static String statusOrNull(JsonNode body) {
    JsonNode value = body.get("status");
    if (value == null || value.isNull()) {
      return null;
    }
    if (!value.isString()) {
      throw ApiException.badRequest("BAD_REQUEST", "status is invalid");
    }
    String status = value.asString();
    if (!status.equals("ACTIVE") && !status.equals("GRACE") && !status.equals("SUSPENDED")) {
      throw ApiException.badRequest("BAD_REQUEST", "status is invalid");
    }
    return status;
  }

  private static Instant instantOrNull(JsonNode body) {
    JsonNode value = body.get("expires_at");
    if (value == null || value.isNull()) {
      return null;
    }
    if (!value.isString()) {
      throw ApiException.badRequest("BAD_REQUEST", "expires_at is invalid");
    }
    try {
      return Instant.parse(value.asString());
    } catch (RuntimeException ex) {
      throw ApiException.badRequest("BAD_REQUEST", "expires_at is invalid");
    }
  }

  private JsonNode readObject(HttpServletRequest request) throws IOException {
    JsonNode node = json.readTree(readRaw(request));
    if (node == null || !node.isObject()) {
      throw ApiException.schema("body must be a JSON object");
    }
    return node;
  }

  private static String readRaw(HttpServletRequest request) throws IOException {
    byte[] raw = request.getInputStream().readNBytes(1024 * 1024 + 1);
    if (raw.length == 0 || raw.length > 1024 * 1024) {
      throw ApiException.badRequest("BAD_REQUEST", "JSON body is required");
    }
    return new String(raw, StandardCharsets.UTF_8);
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || !value.isString() || value.asString().isBlank()) {
      throw ApiException.badRequest("BAD_REQUEST", field + " is required");
    }
    return value.asString();
  }
}
