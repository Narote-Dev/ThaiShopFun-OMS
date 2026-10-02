package com.thaishopfun.mocktsf.rest;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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
  private final ConcurrentHashMap<String, Stored> idempotency = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, Map<String, Object>> shipments =
      new ConcurrentHashMap<>();
  private final AtomicLong sequence = new AtomicLong(1);

  public TsfCatalog() {
    Instant updated = Instant.parse("2026-09-29T08:15:02Z");
    orders.add(order(updated));
    orders.add(activeSecond(updated.plusSeconds(60)));
    orders.add(second(updated.plusSeconds(120)));
    listings.add(listing("tsf_sku_7781", "TSHIRT-BLK-M", "เสื้อยืดดำ M", 18, 1042));
    listings.add(listing("tsf_sku_9001", "MUG-WHT", "แก้วขาว", 4, 880));
    listings.add(listing("tsf_sku_5000", "SET-TSHIRT-2", "เซ็ตเสื้อ 2 ตัว", 9, 1043));
  }

  public boolean knownShop(String shopId) {
    return SHOPS.contains(shopId);
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

  public List<Map<String, Object>> listings() {
    return listings;
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
    detail.put("lines", List.of(line("L1", "tsf_sku_5000", "SET-TSHIRT-2", "เซ็ตเสื้อ 2 ตัว", 1, 450)));
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
