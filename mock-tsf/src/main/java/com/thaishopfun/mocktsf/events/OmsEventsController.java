package com.thaishopfun.mocktsf.events;

import com.thaishopfun.mocktsf.ApiException;
import com.thaishopfun.mocktsf.Hmacs;
import com.thaishopfun.mocktsf.MockProperties;
import com.thaishopfun.mocktsf.contract.ContractResponses;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Section 4.6 receiver. Verifies HMAC, rejects a bad schema, and keeps the raw body. */
@RestController
public class OmsEventsController {

  /** Section 4.6. TSF → OMS types are rejected here even when their schema is valid. */
  static final Set<String> OMS_TO_TSF =
      Set.of("stock.updated", "order.status_changed", "shipment.updated", "return.received");

  private final MockProperties properties;
  private final ContractResponses responses;
  private final ReceivedEventStore store;
  private final JsonMapper json;

  public OmsEventsController(
      MockProperties properties,
      ContractResponses responses,
      ReceivedEventStore store,
      JsonMapper json) {
    this.properties = properties;
    this.responses = responses;
    this.store = store;
    this.json = json;
  }

  @PostMapping("/internal/v1/oms-events")
  public ResponseEntity<String> receive(HttpServletRequest request) throws IOException {
    // Step 1: Signature and clock window. Do not store a request that fails either check.
    byte[] raw = request.getInputStream().readNBytes(1024 * 1024 + 1);
    if (raw.length == 0 || raw.length > 1024 * 1024) {
      throw ApiException.badRequest("BAD_REQUEST", "JSON body is required");
    }
    if (!Hmacs.valid(
        properties.outboxSecrets(),
        request.getHeader("X-Signature"),
        raw,
        Instant.now().getEpochSecond())) {
      throw new ApiException(401, "UNAUTHORIZED", "Invalid signature");
    }
    String body = new String(raw, StandardCharsets.UTF_8);
    // Step 2: NUL is rejected before schema, matching OMS inbox unsupportedJson.
    if (unsupportedJson(body)) {
      throw ApiException.badRequest("BAD_REQUEST", "Body contains an unsupported character");
    }
    // Step 3: Schema, then the 4.6 allowlist. A duplicate event_id is 200.
    responses.requireInboundEnvelope(body);
    JsonNode node = json.readTree(body);
    String eventType = node.path("event_type").asString();
    if (!OMS_TO_TSF.contains(eventType)) {
      throw ApiException.badRequest("BAD_REQUEST", "event_type is not accepted on this receiver");
    }
    String eventId = node.path("event_id").asString();
    String headerId = request.getHeader("X-Event-Id");
    if (headerId == null || !headerId.equals(eventId)) {
      throw ApiException.badRequest("BAD_REQUEST", "X-Event-Id does not match the body");
    }
    boolean created =
        store.add(
            new ReceivedEventStore.Received(eventId, eventType, ReceivedEventStore.now(), body));
    Map<String, String> ack = new LinkedHashMap<>();
    ack.put("status", created ? "accepted" : "duplicate");
    ack.put("event_id", eventId);
    return responses.outbound(created ? 202 : 200, "event-ack", ack);
  }

  static boolean unsupportedJson(String payload) {
    return payload.indexOf('\0') >= 0 || payload.toLowerCase(Locale.ROOT).contains("\\u0000");
  }
}
