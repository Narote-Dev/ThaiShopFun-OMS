package com.thaishopfun.mocktsf.rest;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiFunction;
import org.springframework.stereotype.Component;

/** Seeded orders, listings, and in-memory shipments for section 4.7. */
@Component
public class TsfCatalog {

  private static final Set<String> SHOPS =
      Set.of("shop_active", "shop_grace", "shop_suspended", "shop_expired", "shop_bump");

  private final List<Order> orders = new ArrayList<>();
  private final List<Map<String, Object>> listings = new ArrayList<>();
  private final Set<String> hiddenListings = ConcurrentHashMap.newKeySet();
  private final ConcurrentHashMap<String, Stored> idempotency = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, Map<String, Object>> shipments =
      new ConcurrentHashMap<>();
  private final AtomicLong sequence = new AtomicLong(1);
  private final ConcurrentHashMap<String, CopyOnWriteArrayList<CancelHit>> cancelHits =
      new ConcurrentHashMap<>();
  private final Set<String> extraShops = ConcurrentHashMap.newKeySet();

  public record CancelHit(String idempotencyKey, Instant at) {}

  public void recordCancelHit(String orderId, String idempotencyKey) {
    cancelHits
        .computeIfAbsent(orderId, ignored -> new CopyOnWriteArrayList<>())
        .add(new CancelHit(idempotencyKey, Instant.now()));
  }

  public List<CancelHit> cancelHits(String orderId) {
    CopyOnWriteArrayList<CancelHit> hits = cancelHits.get(orderId);
    return hits == null ? List.of() : List.copyOf(hits);
  }

  public void clearCancelHits(String orderId) {
    cancelHits.remove(orderId);
  }

  public TsfCatalog() {
    Instant updated = Instant.parse("2026-09-29T08:15:02Z");
    orders.add(order(updated));
    orders.add(activeSecond(updated.plusSeconds(60)));
    orders.add(second(updated.plusSeconds(120)));
    listings.add(listing("tsf_sku_7781", "TSHIRT-BLK-M", "เสื้อยืดดำ M", 18, 1042));
    listings.add(listing("tsf_sku_9001", "MUG-WHT", "แก้วขาว", 4, 880));
    listings.add(listing("tsf_sku_5000", "SET-TSHIRT-2", "เซ็ตเสื้อ 2 ตัว", 9, 1043));
    listings.add(listing("L-demo-missing", "DEMO-SKU-MISSING", "Demo unmapped", 0, 2001));
    listings.add(listing("L-demo-ready", "DEMO-SKU-READY", "Demo ready", 50, 3001));
    listings.add(listing("L-demo-cod", "DEMO-SKU-COD", "Demo COD", 50, 3002));
    listings.add(listing("L-demo-oos", "DEMO-SKU-OOS", "Demo OOS", 0, 3003));
    listings.add(listing("L-demo-bundle", "DEMO-SKU-BUNDLE-EMPTY", "Demo bundle", 0, 3004));
    listings.add(listing("L-demo-cancel", "DEMO-SKU-COD", "Demo cancel", 50, 3005));
  }

  public boolean knownShop(String shopId) {
    return SHOPS.contains(shopId) || extraShops.contains(shopId);
  }

  public void registerShop(String shopId) {
    extraShops.add(shopId);
  }

  public synchronized List<String> createBulkOrders(
      String shopId, int count, String paymentMethod, boolean paid) {
    registerShop(shopId);
    if (!knownShop(shopId)) {
      throw new IllegalArgumentException("unknown shop");
    }
    List<String> ids = new ArrayList<>();
    Instant updated = Instant.now();
    for (int i = 0; i < count; i++) {
      String orderId = "TSF-BULK-" + sequence.getAndIncrement();
      long version = paid && "PREPAID".equals(paymentMethod) ? 2L : 1L;
      Map<String, Object> detail = base(orderId, "rsv_" + orderId, updated, version);
      detail.put(
          "lines", List.of(line("L1", "tsf_sku_7781", "TSHIRT-BLK-M", "เสื้อยืดดำ M", 1, 100)));
      detail.put("totals", totals(100, 0, 0, 100));
      detail.put("payment_method", paymentMethod);
      detail.put("status", "ACTIVE");
      if ("PREPAID".equals(paymentMethod)) {
        detail.put("payment_expires_at", updated.plusSeconds(3600).toString());
      } else {
        detail.remove("payment_expires_at");
      }
      String payStatus = paid && "PREPAID".equals(paymentMethod) ? "PAID" : "UNPAID";
      orders.add(new Order(shopId, orderId, updated, version, detail, payment(orderId, payStatus)));
      ids.add(orderId);
    }
    return ids;
  }

  public synchronized void registerOrder(String shopId, String orderId) {
    registerOrder(shopId, orderId, "ACTIVE");
  }

  public synchronized void registerOrder(String shopId, String orderId, String lifecycleStatus) {
    registerShop(shopId);
    if (order(orderId).isPresent()) {
      return;
    }
    Instant updated = Instant.now();
    Map<String, Object> detail = base(orderId, "rsv_" + orderId, updated, 1);
    if ("CANCELLED".equalsIgnoreCase(lifecycleStatus)) {
      detail.put("status", "CANCELLED");
    }
    detail.put(
        "lines", List.of(line("L1", "tsf_sku_7781", "TSHIRT-BLK-M", "เสื้อยืดดำ M", 1, 100)));
    detail.put("totals", totals(100, 0, 0, 100));
    detail.put("payment_method", "PREPAID");
    detail.put("payment_expires_at", updated.plusSeconds(3600).toString());
    orders.add(new Order(shopId, orderId, updated, 1, detail, payment(orderId, "UNPAID")));
  }

  public synchronized void markPaid(String orderId, long aggregateVersion) {
    order(orderId)
        .ifPresent(
            current -> {
              Instant updated = Instant.now();
              Map<String, Object> detail = new LinkedHashMap<>(current.detail());
              detail.put("aggregate_version", aggregateVersion);
              detail.put("updated_at", updated.toString());
              Map<String, Object> pay = payment(orderId, "PAID");
              replaceOrder(
                  new Order(current.shopId(), orderId, updated, aggregateVersion, detail, pay));
            });
  }

  public synchronized void noteOrderEvent(tools.jackson.databind.JsonNode event) {
    String type = event.path("event_type").asString("");
    String shopId = event.path("tsf_shop_id").asString("shop_active");
    String orderId = event.path("data").path("order_id").asString(null);
    if (orderId == null) {
      return;
    }
    tools.jackson.databind.JsonNode data = event.path("data");
    Optional<Order> existing = order(orderId);
    if ("order.created".equals(type) && existing.isEmpty()) {
      registerShop(shopId);
      long version = event.path("aggregate_version").asLong(1);
      Instant updated = Instant.now();
      Map<String, Object> detail = orderDetailFromEvent(data, orderId, updated, version);
      String paymentMethod = data.path("payment_method").asString("COD");
      detail.put("payment_method", paymentMethod);
      if ("PREPAID".equals(paymentMethod)) {
        detail.put("payment_expires_at", updated.plusSeconds(3600).toString());
      } else {
        detail.remove("payment_expires_at");
      }
      orders.add(new Order(shopId, orderId, updated, version, detail, payment(orderId, "UNPAID")));
      return;
    }
    if (existing.isEmpty()) {
      return;
    }
    Order current = existing.get();
    long version = event.path("aggregate_version").asLong(current.aggregateVersion());
    Instant updated = Instant.now();
    Map<String, Object> detail = new LinkedHashMap<>(current.detail());
    detail.put("aggregate_version", version);
    detail.put("updated_at", updated.toString());
    if (data.has("recipient") && data.get("recipient").isObject()) {
      detail.put("recipient", jsonToMap(data.get("recipient")));
    }
    if ("order.cancelled".equals(type)) {
      detail.put("status", "CANCELLED");
    }
    Map<String, Object> pay = new LinkedHashMap<>(current.payment());
    if ("order.paid".equals(type)) {
      pay.put("status", "PAID");
    }
    replaceOrder(new Order(current.shopId(), orderId, updated, version, detail, pay));
  }

  private static Map<String, Object> orderDetailFromEvent(
      tools.jackson.databind.JsonNode data, String orderId, Instant updated, long version) {
    Map<String, Object> detail =
        base(orderId, data.path("reservation_id").asString("rsv_" + orderId), updated, version);
    if (data.has("lines") && data.get("lines").isArray()) {
      detail.put("lines", jsonToList(data.get("lines")));
    }
    if (data.has("recipient") && data.get("recipient").isObject()) {
      detail.put("recipient", jsonToMap(data.get("recipient")));
    }
    return detail;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> jsonToMap(tools.jackson.databind.JsonNode node) {
    return new tools.jackson.databind.json.JsonMapper().convertValue(node, Map.class);
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> jsonToList(tools.jackson.databind.JsonNode node) {
    List<Map<String, Object>> lines = new ArrayList<>();
    for (tools.jackson.databind.JsonNode line : node) {
      lines.add(jsonToMap(line));
    }
    return lines;
  }

  private void replaceOrder(Order replacement) {
    for (int i = 0; i < orders.size(); i++) {
      if (orders.get(i).orderId().equals(replacement.orderId())) {
        orders.set(i, replacement);
        return;
      }
    }
    orders.add(replacement);
  }

  public List<Order> orders(String shopId, Instant updatedSince) {
    List<Order> matched = new ArrayList<>();
    for (Order order : orders) {
      if (!order.shopId().equals(shopId)) {
        continue;
      }
      if (updatedSince != null && order.updatedAt().isBefore(updatedSince)) {
        continue;
      }
      matched.add(order);
    }
    return matched;
  }

  public Optional<Order> order(String orderId) {
    for (Order order : orders) {
      if (order.orderId().equals(orderId)) {
        return Optional.of(order);
      }
    }
    return Optional.empty();
  }

  /** Lets section 4.7 cancel-requests accept local DEMO-* ids used by orders-seed. */
  public synchronized void ensureDemoOrder(String orderId) {
    if (order(orderId).isPresent()) {
      return;
    }
    Instant updated = Instant.now();
    Map<String, Object> detail = base(orderId, "res-" + orderId, updated, 1);
    detail.put("lines", List.of(line("L1", "L-demo", "SKU-DEMO", "Demo item", 1, 100)));
    detail.put("totals", totals(100, 0, 0, 100));
    detail.put("payment_method", "COD");
    orders.add(new Order("shop_active", orderId, updated, 1, detail, payment(orderId, "UNPAID")));
  }

  public List<Map<String, Object>> listings() {
    List<Map<String, Object>> visible = new ArrayList<>();
    for (Map<String, Object> row : listings) {
      String id = String.valueOf(row.get("listing_sku_id"));
      if (!hiddenListings.contains(id)) {
        visible.add(row);
      }
    }
    return visible;
  }

  public void setListingHidden(String listingSkuId, boolean hidden) {
    if (hidden) {
      hiddenListings.add(listingSkuId);
    } else {
      hiddenListings.remove(listingSkuId);
    }
  }

  /**
   * Lookup and insert for one scope+key are a single map operation. The function runs once and must
   * return the stored row (the previous one, or the row just created).
   */
  public Stored compute(String scope, String key, BiFunction<String, Stored, Stored> remapping) {
    return idempotency.compute(scope + "\n" + key, remapping);
  }

  public Map<String, Object> newShipment(String orderId, String carrier) {
    String id = "shp_" + sequence.getAndIncrement();
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("shipment_id", id);
    body.put("order_id", orderId);
    body.put("carrier", carrier);
    body.put("tracking_no", "TH" + id);
    body.put("status", "LABEL_READY");
    shipments.put(id, body);
    return body;
  }

  public Optional<Map<String, Object>> shipment(String shipmentId) {
    return Optional.ofNullable(shipments.get(shipmentId));
  }

  public Map<String, Object> newCancel(String orderId) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("cancel_request_id", "cxl_" + sequence.getAndIncrement());
    body.put("order_id", orderId);
    body.put("status", "PENDING");
    return body;
  }

  public record Order(
      String shopId,
      String orderId,
      Instant updatedAt,
      long aggregateVersion,
      Map<String, Object> detail,
      Map<String, Object> payment) {}

  public record Stored(String bodyHash, int status, String response) {}

  private static Order order(Instant updated) {
    Map<String, Object> detail = base("TSF-240929-000123", "rsv_01J9Z4A1B2C3", updated, 3);
    Map<String, Object> line = line("L1", "tsf_sku_7781", "TSHIRT-BLK-M", "เสื้อยืดดำ M", 2, 295);
    detail.put("lines", List.of(line));
    detail.put("totals", totals(590, 40, 50, 580));
    detail.put("payment_method", "PREPAID");
    detail.put("payment_expires_at", "2026-09-29T08:45:00Z");
    return new Order(
        "shop_active",
        "TSF-240929-000123",
        updated,
        3,
        detail,
        payment("TSF-240929-000123", "PAID"));
  }

  private static Order activeSecond(Instant updated) {
    Map<String, Object> detail = base("TSF-240929-000125", "rsv_01J9Z4A1B2C5", updated, 1);
    detail.put(
        "lines", List.of(line("L1", "tsf_sku_5000", "SET-TSHIRT-2", "เซ็ตเสื้อ 2 ตัว", 1, 450)));
    detail.put("totals", totals(450, 40, 0, 490));
    detail.put("payment_method", "PREPAID");
    return new Order(
        "shop_active",
        "TSF-240929-000125",
        updated,
        1,
        detail,
        payment("TSF-240929-000125", "PAID"));
  }

  private static Order second(Instant updated) {
    Map<String, Object> detail = base("TSF-240929-000124", "rsv_01J9Z4A1B2C4", updated, 1);
    detail.put("lines", List.of(line("L1", "tsf_sku_9001", "MUG-WHT", "แก้วขาว", 1, 190)));
    detail.put("totals", totals(190, 40, 0, 230));
    detail.put("payment_method", "COD");
    detail.remove("payment_expires_at");
    return new Order(
        "shop_grace",
        "TSF-240929-000124",
        updated,
        1,
        detail,
        payment("TSF-240929-000124", "UNPAID"));
  }

  private static Map<String, Object> base(
      String orderId, String reservationId, Instant updated, long version) {
    Map<String, Object> detail = new LinkedHashMap<>();
    detail.put("order_id", orderId);
    detail.put("reservation_id", reservationId);
    detail.put("currency", "THB");
    detail.put("payment_expires_at", "2026-09-29T08:45:00Z");
    detail.put(
        "recipient",
        Map.of(
            "name",
            "สมชาย ใจดี",
            "phone",
            "0812341234",
            "address",
            Map.of(
                "line1",
                "99/1 ถ.สุขุมวิท",
                "district",
                "คลองเตย",
                "province",
                "กรุงเทพมหานคร",
                "postcode",
                "10110")));
    detail.put("ship_by", "2026-10-01T10:59:59Z");
    detail.put("updated_at", updated.toString());
    detail.put("aggregate_version", version);
    detail.put("status", "ACTIVE");
    return detail;
  }

  private static Map<String, Object> line(
      String lineId, String listing, String seller, String name, int qty, int price) {
    Map<String, Object> line = new LinkedHashMap<>();
    line.put("line_id", lineId);
    line.put("listing_sku_id", listing);
    line.put("seller_sku", seller);
    line.put("name", name);
    line.put("qty", qty);
    line.put("unit_price", price);
    return line;
  }

  private static Map<String, Object> totals(int subtotal, int shipping, int discount, int grand) {
    Map<String, Object> totals = new LinkedHashMap<>();
    totals.put("subtotal", subtotal);
    totals.put("shipping_fee", shipping);
    totals.put("discount", discount);
    totals.put("grand_total", grand);
    return totals;
  }

  private static Map<String, Object> payment(String orderId, String status) {
    Map<String, Object> payment = new LinkedHashMap<>();
    payment.put("order_id", orderId);
    payment.put("status", status);
    if ("PAID".equals(status)) {
      Map<String, Object> refund = new LinkedHashMap<>();
      refund.put("refund_id", "xnd_rf_77");
      refund.put("return_id", "tsf_ret_311");
      refund.put("status", "SUCCEEDED");
      refund.put("amount", 295);
      refund.put("currency", "THB");
      payment.put("refunds", List.of(refund));
    } else {
      payment.put("refunds", List.of());
    }
    return payment;
  }

  private static Map<String, Object> listing(
      String id, String seller, String name, int available, int version) {
    Map<String, Object> listing = new LinkedHashMap<>();
    listing.put("listing_sku_id", id);
    listing.put("seller_sku", seller);
    listing.put("name", name);
    listing.put("available", available);
    listing.put("stock_version", version);
    return listing;
  }
}
