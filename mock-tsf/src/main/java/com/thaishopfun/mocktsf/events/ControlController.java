package com.thaishopfun.mocktsf.events;

import com.thaishopfun.mocktsf.ApiException;
import com.thaishopfun.mocktsf.Hmacs;
import com.thaishopfun.mocktsf.MockProperties;
import com.thaishopfun.mocktsf.SeedData;
import com.thaishopfun.mocktsf.contract.ContractResponses;
import com.thaishopfun.mocktsf.idp.TokenIssuer;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
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

  public ControlController(
      JsonMapper json,
      ContractResponses responses,
      OmsCaller oms,
      MockProperties properties,
      SeedData shops,
      TokenIssuer tokens,
      ReceivedEventStore received) {
    this.json = json;
    this.responses = responses;
    this.oms = oms;
    this.properties = properties;
    this.shops = shops;
    this.tokens = tokens;
    this.received = received;
  }

  @PostMapping("/user-token")
  public ResponseEntity<String> userToken(HttpServletRequest request) throws IOException {
    JsonNode body = readObject(request);
    String hint = text(body, "login_hint");
    SeedData.ShopUser user =
        shops
            .find(hint)
            .orElseThrow(() -> ApiException.badRequest("UNKNOWN_USER", "login_hint is unknown"));
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
      sent.add(row(eventId, oms.postEvent(raw, eventId, signature)));
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
      sent.add(row(ids.get(index), oms.postEvent(raw, ids.get(index), oms.signInbox(now, raw))));
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
            row(eventId(raw), oms.postEvent(raw, eventId(raw), oms.signInbox(timestamp, raw)))));
  }

  @PostMapping("/events/bad-signature")
  public ResponseEntity<String> badSignature(HttpServletRequest request) throws IOException {
    byte[] raw = canonical(eventOf(readObject(request)));
    String signature = Hmacs.header("invalid-signature-secret", OmsCaller.now(), raw);
    return report(List.of(row(eventId(raw), oms.postEvent(raw, eventId(raw), signature))));
  }

  @PostMapping("/events/after-reservation-expiry")
  public ResponseEntity<String> afterExpiry(HttpServletRequest request) throws IOException {
    // Step 1: Stamp occurred_at strictly after the reservation expiry. Do not sleep for the TTL.
    JsonNode body = readObject(request);
    String expiryText = text(body, "reservation_expires_at");
    Instant expiry;
    try {
      expiry = Instant.parse(expiryText);
    } catch (Exception ex) {
      throw ApiException.badRequest("BAD_REQUEST", "reservation_expires_at is invalid");
    }
    ObjectNode event = eventOf(body);
    Instant occurred;
    try {
      occurred =
          event.hasNonNull("occurred_at")
              ? Instant.parse(event.get("occurred_at").asString())
              : Instant.EPOCH;
    } catch (Exception ex) {
      throw ApiException.badRequest("BAD_REQUEST", "occurred_at is invalid");
    }
    if (!occurred.isAfter(expiry)) {
      event.put("occurred_at", expiry.plusSeconds(1).toString());
    }
    return report(List.of(deliver(event, OmsCaller.now(), true)));
  }

  @PostMapping("/checkout/reservations")
  public ResponseEntity<String> reserve(HttpServletRequest request) throws IOException {
    String raw = readRaw(request);
    responses.requireInboundRest("reservation-request", raw);
    JsonNode body = json.readTree(raw);
    String checkoutId = body.path("checkout_id").asString();
    OmsCaller.CallResult result =
        oms.postReservation(raw.getBytes(StandardCharsets.UTF_8), checkoutId);
    return responses.outbound(200, "checkout-result", checkoutResult(result));
  }

  @DeleteMapping("/checkout/reservations/{reservationId}")
  public ResponseEntity<String> release(@PathVariable String reservationId) {
    if (reservationId.isBlank()) {
      throw ApiException.badRequest("BAD_REQUEST", "reservation id is required");
    }
    return responses.outbound(
        200, "checkout-result", checkoutResult(oms.deleteReservation(reservationId)));
  }

  @GetMapping("/received-events")
  public ResponseEntity<String> received() {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("events", received.view());
    return responses.outbound(200, "received-events", body);
  }

  private Map<String, Object> deliver(ObjectNode event, long timestamp, boolean validSignature) {
    byte[] raw = canonical(event);
    String eventId = eventId(raw);
    String signature =
        validSignature
            ? oms.signInbox(timestamp, raw)
            : Hmacs.header("invalid-signature-secret", timestamp, raw);
    return row(eventId, oms.postEvent(raw, eventId, signature));
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

  private static Map<String, Object> row(String eventId, OmsCaller.CallResult result) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("event_id", eventId);
    row.put("http_status", result.status());
    row.put("body", result.body());
    return row;
  }

  private Map<String, Object> checkoutResult(OmsCaller.CallResult result) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("oms_status", result.status());
    body.put("oms_body", result.body());
    body.put("response_schema_valid", schemaOk(result));
    return body;
  }

  private boolean schemaOk(OmsCaller.CallResult result) {
    if (!result.reached()) {
      return false;
    }
    if (result.status() == 204) {
      return result.body() == null || result.body().isBlank();
    }
    if (result.status() == 201) {
      return responses.validator().restErrors("reservation-created", result.body()).isEmpty();
    }
    if (result.status() == 409) {
      return responses.validator().restErrors("reservation-conflict", result.body()).isEmpty()
          || responses.validator().restErrors("error", result.body()).isEmpty();
    }
    return true;
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
