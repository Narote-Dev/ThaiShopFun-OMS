package com.thaishopfun.oms.checkout;

import com.thaishopfun.oms.auth.UuidV7;
import com.thaishopfun.oms.checkout.CheckoutOutOfStockException.ItemConflict;
import com.thaishopfun.oms.checkout.CheckoutRepository.ChannelAccount;
import com.thaishopfun.oms.checkout.CheckoutRepository.ListingRow;
import com.thaishopfun.oms.checkout.CheckoutRepository.TenantRow;
import com.thaishopfun.oms.stock.IdempotencyConflictException;
import com.thaishopfun.oms.stock.ReservationEngine;
import com.thaishopfun.oms.stock.ReserveItem;
import com.thaishopfun.oms.stock.ReserveResult;
import com.thaishopfun.oms.stock.Shortfall;
import com.thaishopfun.oms.stock.StockAvailability;
import com.thaishopfun.oms.stock.StockError;
import com.thaishopfun.oms.stock.StockOperationException;
import com.thaishopfun.oms.stock.StockOwner;
import com.thaishopfun.oms.stock.StockProperties;
import com.thaishopfun.oms.stock.StockRetry;
import com.thaishopfun.oms.tenant.TenantContext;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

@Service
public class CheckoutReserveService {

  public static final String RESERVE_TIMER = "oms.checkout.reserve";
  public static final String REPLAY_COUNTER = "oms.checkout.idempotent_replay";

  private static final Logger log = LoggerFactory.getLogger(CheckoutReserveService.class);
  private static final int MAX_ITEMS = 100;
  private static final String TSF_CHANNEL = "TSF";

  private final CheckoutRepository repository;
  private final CheckoutIdempotency idempotency;
  private final ReservationEngine engine;
  private final StockAvailability availability;
  private final StockRetry retry;
  private final StockProperties stockProperties;
  private final TransactionTemplate writeTx;
  private final JsonMapper json;
  private final Clock clock;
  private final Counter replays;
  private final MeterRegistry meters;

  CheckoutReserveService(
      CheckoutRepository repository,
      CheckoutIdempotency idempotency,
      ReservationEngine engine,
      StockAvailability availability,
      StockRetry retry,
      StockProperties stockProperties,
      PlatformTransactionManager transactions,
      JsonMapper json,
      Clock clock,
      MeterRegistry meters) {
    this.repository = repository;
    this.idempotency = idempotency;
    this.engine = engine;
    this.availability = availability;
    this.retry = retry;
    this.stockProperties = stockProperties;
    this.writeTx = new TransactionTemplate(transactions);
    this.writeTx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    this.json = json;
    this.clock = clock;
    this.replays = Counter.builder(REPLAY_COUNTER).register(meters);
    this.meters = meters;
  }

  public ReserveHttpResult reserve(String idempotencyHeader, JsonNode body) {
    ReserveRequest request = parse(body);
    if (idempotencyHeader == null
        || idempotencyHeader.isBlank()
        || !idempotencyHeader.equals(request.checkoutId())) {
      throw new CheckoutBadRequestException("Idempotency-Key must equal checkout_id");
    }
    UUID tenantId = repository.resolveTenant(TSF_CHANNEL, request.tsfShopId());
    if (tenantId == null) {
      return unenforcedUnknownShop(request);
    }
    TenantContext.set(tenantId, null);
    Timer.Sample sample = Timer.start(meters);
    try {
      String hash = requestHash(request);
      ReserveHttpResult result =
          retry.execute(
              "checkout.reserve",
              () ->
                  writeTx.execute(
                      status -> {
                        try {
                          return reserveInTransaction(tenantId, request, hash);
                        } catch (StockOperationException ex) {
                          if (ex.error() == StockError.OWNER_ALREADY_RESERVED) {
                            throw new IdempotencyConflictException(CheckoutIdempotency.SCOPE);
                          }
                          throw ex;
                        }
                      }));
      sample.stop(
          Timer.builder(RESERVE_TIMER)
              .tag("outcome", result.outcome())
              .tag("mode", result.mode())
              .register(meters));
      return result;
    } finally {
      TenantContext.clear();
    }
  }

  public void release(String reservationIdRaw) {
    UUID groupId = parseGroupId(reservationIdRaw);
    if (groupId == null) {
      return;
    }
    UUID tenantId = repository.resolveReservationTenant(groupId);
    if (tenantId == null) {
      return;
    }
    TenantContext.set(tenantId, null);
    try {
      engine.release(groupId, "release:" + reservationIdRaw.trim());
    } catch (StockOperationException ex) {
      log.info("checkout release ignored: {}", ex.getMessage());
    } finally {
      TenantContext.clear();
    }
  }

  private ReserveHttpResult reserveInTransaction(
      UUID tenantId, ReserveRequest request, String hash) {
    CheckoutIdempotency.Stored stored = idempotency.claim(tenantId, request.checkoutId(), hash);
    if (stored != null) {
      replays.increment();
      String outcome = "replay";
      if (stored.body().has("error")
          && "OUT_OF_STOCK".equals(stored.body().get("error").asString())) {
        outcome = "out_of_stock";
      } else if (stored.status() == 201 && !stored.body().path("enforced").asBoolean(false)) {
        outcome = "unenforced";
      } else if (stored.status() == 201) {
        outcome = "reserved";
      } else if (stored.status() == 409) {
        outcome = "conflict";
      }
      return new ReserveHttpResult(stored.status(), stored.body(), outcome, "replay");
    }
    TenantRow tenant =
        repository
            .tenant(tenantId)
            .orElseThrow(() -> new IllegalStateException("tenant " + tenantId + " missing"));
    Optional<ChannelAccount> account = repository.tsfAccount(tenantId);
    String modeTag = account.map(ChannelAccount::mode).orElse("none");
    List<ItemPlan> plans = planItems(tenant, account, request);
    Instant expiresAt =
        clock.instant().plus(stockProperties.getCheckoutTtl()).truncatedTo(ChronoUnit.MICROS);

    if (account.isPresent() && "SHADOW".equals(account.get().mode())) {
      ObjectNode omsValue = shadowOmsValue(plans);
      repository.insertShadowDiff(
          tenantId, account.get().id(), request.checkoutId(), omsValue, clock.instant());
      ObjectNode response = createdResponse(UuidV7.generate(), expiresAt, plans);
      idempotency.complete(tenantId, request.checkoutId(), 201, response);
      return new ReserveHttpResult(201, response, "unenforced", modeTag);
    }

    boolean anyEnforced = plans.stream().anyMatch(ItemPlan::enforced);
    if (!anyEnforced) {
      ObjectNode response = createdResponse(UuidV7.generate(), expiresAt, plans);
      idempotency.complete(tenantId, request.checkoutId(), 201, response);
      return new ReserveHttpResult(201, response, "unenforced", modeTag);
    }

    List<ReserveItem> reserveItems = new ArrayList<>();
    for (ItemPlan plan : plans) {
      if (plan.enforced()) {
        reserveItems.add(ReserveItem.of(plan.skuId(), plan.qty()));
      }
    }
    StockOwner owner = StockOwner.checkout(request.checkoutId());
    ReserveResult result = engine.reserve(owner, reserveItems, request.checkoutId());
    if (!result.reserved()) {
      List<ItemConflict> conflicts = listingConflicts(plans, result.shortfalls());
      ObjectNode conflict = conflictBody(conflicts);
      idempotency.complete(tenantId, request.checkoutId(), 409, conflict);
      return new ReserveHttpResult(409, conflict, "out_of_stock", modeTag);
    }
    ObjectNode response = createdResponse(result.reservationGroupId(), result.expiresAt(), plans);
    idempotency.complete(tenantId, request.checkoutId(), 201, response);
    return new ReserveHttpResult(201, response, "reserved", modeTag);
  }

  private ReserveHttpResult unenforcedUnknownShop(ReserveRequest request) {
    Instant expiresAt =
        clock.instant().plus(stockProperties.getCheckoutTtl()).truncatedTo(ChronoUnit.MICROS);
    List<ItemPlan> plans =
        request.items().stream()
            .map(item -> new ItemPlan(item.listingSkuId(), item.qty(), false, null))
            .toList();
    ObjectNode body = createdResponse(UuidV7.generate(), expiresAt, plans);
    return new ReserveHttpResult(201, body, "unenforced", "unknown");
  }

  private List<ItemPlan> planItems(
      TenantRow tenant, Optional<ChannelAccount> account, ReserveRequest request) {
    if (!entitlementEnforces(tenant)) {
      return unenforcedPlans(request);
    }
    if (account.isEmpty()
        || "DISCONNECTED".equals(account.get().status())
        || "OBSERVE".equals(account.get().mode())) {
      return unenforcedPlans(request);
    }
    if (!repository.hasDefaultWarehouse()) {
      return unenforcedPlans(request);
    }
    String mode = account.get().mode();
    UUID tenantId = TenantContext.requireTenantId();
    UUID accountId = account.get().id();
    List<ItemPlan> plans = new ArrayList<>();
    for (RequestItem item : request.items()) {
      Optional<ListingRow> listing = repository.listing(tenantId, accountId, item.listingSkuId());
      UUID skuId = listing.map(ListingRow::skuId).orElse(null);
      boolean enforced =
          skuId != null
              && ("ACTIVE".equals(mode)
                  || ("CONTROL".equals(mode) && listing.get().stockControl()));
      if ("SHADOW".equals(mode)) {
        enforced = false;
      }
      plans.add(new ItemPlan(item.listingSkuId(), item.qty(), enforced, skuId));
    }
    return plans;
  }

  private static List<ItemPlan> unenforcedPlans(ReserveRequest request) {
    return request.items().stream()
        .map(item -> new ItemPlan(item.listingSkuId(), item.qty(), false, null))
        .toList();
  }

  private boolean entitlementEnforces(TenantRow tenant) {
    if ("SUSPENDED".equals(tenant.entitlementStatus())) {
      return false;
    }
    if (tenant.entitlementExpiresAt() != null
        && tenant.entitlementExpiresAt().isBefore(clock.instant())) {
      return false;
    }
    return "ACTIVE".equals(tenant.entitlementStatus())
        || "GRACE".equals(tenant.entitlementStatus());
  }

  private ObjectNode shadowOmsValue(List<ItemPlan> plans) {
    boolean wouldReserve = true;
    ArrayNode items = json.createArrayNode();
    for (ItemPlan plan : plans) {
      int available = 0;
      if (plan.skuId() != null) {
        available = availability.available(plan.skuId(), null);
      }
      boolean itemWould = plan.skuId() != null && plan.qty() <= available;
      if (!itemWould) {
        wouldReserve = false;
      }
      ObjectNode row = json.createObjectNode();
      row.put("listing_sku_id", plan.listingSkuId());
      row.put("requested", plan.qty());
      row.put("available", available);
      row.put("would_reserve", itemWould);
      items.add(row);
    }
    ObjectNode omsValue = json.createObjectNode();
    omsValue.set("items", items);
    omsValue.put("would_reserve", wouldReserve);
    return omsValue;
  }

  private List<ItemConflict> listingConflicts(List<ItemPlan> plans, List<Shortfall> shortfalls) {
    Map<UUID, String> skuToListing = new LinkedHashMap<>();
    for (ItemPlan plan : plans) {
      if (plan.skuId() != null) {
        skuToListing.putIfAbsent(plan.skuId(), plan.listingSkuId());
      }
    }
    Map<String, ItemConflict> byListing = new LinkedHashMap<>();
    for (Shortfall shortfall : shortfalls) {
      for (UUID skuId : shortfall.requestedBy()) {
        String listingId = skuToListing.get(skuId);
        if (listingId == null) {
          continue;
        }
        int listingAvailable = availability.available(skuId, shortfall.warehouseId());
        byListing.putIfAbsent(
            listingId, new ItemConflict(listingId, planQty(plans, listingId), listingAvailable));
      }
    }
    return List.copyOf(byListing.values());
  }

  private static int planQty(List<ItemPlan> plans, String listingSkuId) {
    for (ItemPlan plan : plans) {
      if (plan.listingSkuId().equals(listingSkuId)) {
        return plan.qty();
      }
    }
    return 0;
  }

  private ObjectNode conflictBody(List<ItemConflict> conflicts) {
    ObjectNode body = json.createObjectNode();
    body.put("error", "OUT_OF_STOCK");
    ArrayNode items = json.createArrayNode();
    for (ItemConflict conflict : conflicts) {
      ObjectNode row = json.createObjectNode();
      row.put("listing_sku_id", conflict.listingSkuId());
      row.put("requested", conflict.requested());
      row.put("available", conflict.available());
      items.add(row);
    }
    body.set("items", items);
    return body;
  }

  private ObjectNode createdResponse(UUID groupId, Instant expiresAt, List<ItemPlan> plans) {
    ObjectNode body = json.createObjectNode();
    body.put("reservation_id", groupId.toString());
    body.put("expires_at", expiresAt.toString());
    body.put("enforced", plans.stream().allMatch(ItemPlan::enforced));
    ArrayNode items = json.createArrayNode();
    for (ItemPlan plan : plans) {
      ObjectNode row = json.createObjectNode();
      row.put("listing_sku_id", plan.listingSkuId());
      row.put("qty", plan.qty());
      row.put("enforced", plan.enforced());
      items.add(row);
    }
    body.set("items", items);
    return body;
  }

  static String requestHash(ReserveRequest request) {
    List<RequestItem> sorted = new ArrayList<>(request.items());
    sorted.sort(Comparator.comparing(RequestItem::listingSkuId));
    StringBuilder canonical = new StringBuilder();
    canonical.append(request.checkoutId()).append('|').append(request.tsfShopId());
    for (RequestItem item : sorted) {
      canonical.append('|').append(item.listingSkuId()).append(':').append(item.qty());
    }
    return CheckoutKeys.sha256(canonical.toString());
  }

  private ReserveRequest parse(JsonNode body) {
    if (body == null || !body.isObject()) {
      throw new CheckoutBadRequestException("body must be a JSON object");
    }
    String checkoutId = text(body, "checkout_id");
    String shopId = text(body, "tsf_shop_id");
    JsonNode itemsNode = body.get("items");
    if (itemsNode == null || !itemsNode.isArray() || itemsNode.isEmpty()) {
      throw new CheckoutBadRequestException("items are required");
    }
    if (itemsNode.size() > MAX_ITEMS) {
      throw new CheckoutBadRequestException("at most " + MAX_ITEMS + " items");
    }
    List<RequestItem> items = new ArrayList<>();
    Set<String> seen = new LinkedHashSet<>();
    for (JsonNode item : itemsNode) {
      String listingSkuId = text(item, "listing_sku_id");
      if (!seen.add(listingSkuId)) {
        throw new CheckoutBadRequestException("duplicate listing_sku_id");
      }
      JsonNode qtyNode = item.get("qty");
      if (qtyNode == null || !qtyNode.isIntegralNumber() || qtyNode.asInt() < 1) {
        throw new CheckoutBadRequestException("qty must be a positive integer");
      }
      items.add(new RequestItem(listingSkuId, qtyNode.asInt()));
    }
    return new ReserveRequest(checkoutId, shopId, List.copyOf(items));
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || !value.isString() || value.asString().isBlank()) {
      throw new CheckoutBadRequestException(field + " is required");
    }
    return value.asString();
  }

  private static UUID parseGroupId(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return UUID.fromString(raw.trim());
    } catch (IllegalArgumentException ex) {
      return null;
    }
  }

  record ReserveRequest(String checkoutId, String tsfShopId, List<RequestItem> items) {}

  record RequestItem(String listingSkuId, int qty) {}

  record ItemPlan(String listingSkuId, int qty, boolean enforced, UUID skuId) {}

  public record ReserveHttpResult(int status, JsonNode body, String outcome, String mode) {}
}
