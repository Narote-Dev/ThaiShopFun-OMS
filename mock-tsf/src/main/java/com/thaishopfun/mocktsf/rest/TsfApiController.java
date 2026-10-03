package com.thaishopfun.mocktsf.rest;

import com.thaishopfun.mocktsf.ApiException;
import com.thaishopfun.mocktsf.contract.ContractResponses;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Section 4.7. Client credentials ({@code aud=tsf-internal}) are enforced by the security chain.
 */
@RestController
@RequestMapping("/internal/v1")
public class TsfApiController {

  private static final byte[] LABEL_PDF =
      ("%PDF-1.1\n"
              + "1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj\n"
              + "2 0 obj<</Type/Pages/Count 0>>endobj\n"
              + "trailer<</Root 1 0 R>>\n"
              + "%%EOF\n")
          .getBytes(StandardCharsets.US_ASCII);

  private final TsfCatalog catalog;
  private final ContractResponses responses;
  private final JsonMapper json;

  public TsfApiController(TsfCatalog catalog, ContractResponses responses, JsonMapper json) {
    this.catalog = catalog;
    this.responses = responses;
    this.json = json;
  }

  @GetMapping("/shops/{shopId}/orders")
  public ResponseEntity<String> orders(
      @PathVariable String shopId,
      @RequestParam(value = "updated_since", required = false) String updatedSince,
      @RequestParam(value = "cursor", required = false) String cursor,
      @RequestParam(value = "limit", required = false) Integer limit) {
    // Step 1: Page the seeded orders. An unknown cursor or clock is a client error.
    if (!catalog.knownShop(shopId)) {
      throw new ApiException(404, "SHOP_NOT_FOUND", "shop is unknown");
    }
    int size = limit == null ? 100 : limit;
    if (size < 1 || size > 100) {
      throw ApiException.badRequest("BAD_REQUEST", "limit must be between 1 and 100");
    }
    Instant since = parseTime(updatedSince, "updated_since");
    int offset = decodeCursor(cursor);
    List<TsfCatalog.Order> all = catalog.orders(shopId, since);
    if (offset > all.size()) {
      throw ApiException.badRequest("BAD_REQUEST", "cursor is invalid");
    }
    int end = Math.min(offset + size, all.size());
    List<Map<String, Object>> page = new java.util.ArrayList<>();
    for (TsfCatalog.Order order : all.subList(offset, end)) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("order_id", order.orderId());
      row.put("updated_at", order.updatedAt().toString());
      row.put("aggregate_version", order.aggregateVersion());
      page.add(row);
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("orders", page);
    body.put("next_cursor", end < all.size() ? encodeCursor(end) : null);
    return responses.outbound(200, "order-page", body);
  }

  @GetMapping("/orders/{orderId}")
  public ResponseEntity<String> order(@PathVariable String orderId) {
    TsfCatalog.Order order = requireOrder(orderId);
    return responses.outbound(200, "order", order.detail());
  }

  @GetMapping("/orders/{orderId}/payment-status")
  public ResponseEntity<String> payment(@PathVariable String orderId) {
    TsfCatalog.Order order = requireOrder(orderId);
    return responses.outbound(200, "payment-status", order.payment());
  }

  @GetMapping("/shops/{shopId}/listings")
  public ResponseEntity<String> listings(
      @PathVariable String shopId,
      @RequestParam(value = "cursor", required = false) String cursor,
      @RequestParam(value = "limit", required = false) Integer limit) {
    if (!catalog.knownShop(shopId)) {
      throw new ApiException(404, "SHOP_NOT_FOUND", "shop is unknown");
    }
    int size = limit == null ? 100 : limit;
    if (size < 1 || size > 100) {
      throw ApiException.badRequest("BAD_REQUEST", "limit must be between 1 and 100");
    }
    int offset = decodeCursor(cursor);
    List<Map<String, Object>> all = catalog.listings();
    if (offset > all.size()) {
      throw ApiException.badRequest("BAD_REQUEST", "cursor is invalid");
    }
    int end = Math.min(offset + size, all.size());
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("listings", all.subList(offset, end));
    body.put("next_cursor", end < all.size() ? encodeCursor(end) : null);
    return responses.outbound(200, "listing-page", body);
  }

  @PostMapping("/orders/{orderId}/shipments")
  public ResponseEntity<String> createShipment(
      @PathVariable String orderId,
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      HttpServletRequest request)
      throws IOException {
    // Step 1: Same key and body returns the stored response. A different body is a conflict.
    requireOrder(orderId);
    String raw = read(request);
    responses.requireInboundRest("shipment-request", raw);
    JsonNode body = json.readTree(raw);
    return idempotent(
        "shipment:" + orderId,
        key,
        raw,
        () ->
            responses.outbound(
                201, "shipment", catalog.newShipment(orderId, body.path("carrier").asString())));
  }

  @GetMapping("/shipments/{shipmentId}/label")
  public ResponseEntity<byte[]> label(@PathVariable String shipmentId) {
    catalog
        .shipment(shipmentId)
        .orElseThrow(() -> new ApiException(404, "SHIPMENT_NOT_FOUND", "shipment is unknown"));
    return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF).body(LABEL_PDF);
  }

  @PostMapping("/orders/{orderId}/cancel-requests")
  public ResponseEntity<String> cancel(
      @PathVariable String orderId,
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      HttpServletRequest request)
      throws IOException {
    requireOrder(orderId);
    String raw = read(request);
    responses.requireInboundRest("cancel-request", raw);
    if (key != null && !key.isBlank()) {
      catalog.recordCancelHit(orderId, key);
    }
    return idempotent(
        "cancel:" + orderId,
        key,
        raw,
        () -> responses.outbound(202, "cancel-response", catalog.newCancel(orderId)));
  }

  private ResponseEntity<String> idempotent(
      String scope,
      String key,
      String raw,
      java.util.function.Supplier<ResponseEntity<String>> call) {
    if (key == null || key.isBlank()) {
      throw ApiException.badRequest("IDEMPOTENCY_KEY_REQUIRED", "Idempotency-Key is required");
    }
    String hash = sha256(raw);
    // Step 2: One map operation. Two callers with the same key cannot both create a new id.
    TsfCatalog.Stored stored =
        catalog.compute(
            scope,
            key,
            (ignored, existing) -> {
              if (existing != null) {
                if (!existing.bodyHash().equals(hash)) {
                  throw new ApiException(
                      409,
                      "IDEMPOTENCY_CONFLICT",
                      "Idempotency-Key was reused with a different body");
                }
                return existing;
              }
              ResponseEntity<String> created = call.get();
              return new TsfCatalog.Stored(
                  hash, created.getStatusCode().value(), created.getBody());
            });
    return ResponseEntity.status(stored.status())
        .contentType(MediaType.APPLICATION_JSON)
        .body(stored.response());
  }

  private TsfCatalog.Order requireOrder(String orderId) {
    return catalog
        .order(orderId)
        .orElseThrow(() -> new ApiException(404, "ORDER_NOT_FOUND", "order is unknown"));
  }

  private static String read(HttpServletRequest request) throws IOException {
    byte[] raw = request.getInputStream().readNBytes(1024 * 1024 + 1);
    if (raw.length == 0 || raw.length > 1024 * 1024) {
      throw ApiException.badRequest("BAD_REQUEST", "JSON body is required");
    }
    return new String(raw, StandardCharsets.UTF_8);
  }

  private static Instant parseTime(String value, String field) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return Instant.parse(value);
    } catch (DateTimeParseException ex) {
      throw ApiException.badRequest("BAD_REQUEST", field + " is invalid");
    }
  }

  private static int decodeCursor(String cursor) {
    if (cursor == null || cursor.isBlank()) {
      return 0;
    }
    try {
      String text = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.US_ASCII);
      int offset = Integer.parseInt(text);
      if (offset < 0) {
        throw ApiException.badRequest("BAD_REQUEST", "cursor is invalid");
      }
      return offset;
    } catch (IllegalArgumentException ex) {
      throw ApiException.badRequest("BAD_REQUEST", "cursor is invalid");
    }
  }

  private static String encodeCursor(int offset) {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(Integer.toString(offset).getBytes(StandardCharsets.US_ASCII));
  }

  private static String sha256(String raw) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (Exception ex) {
      throw new IllegalStateException("SHA-256 is unavailable");
    }
  }
}
