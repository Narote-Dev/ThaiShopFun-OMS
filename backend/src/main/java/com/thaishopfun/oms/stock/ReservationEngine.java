package com.thaishopfun.oms.stock;

import com.thaishopfun.oms.auth.UuidV7;
import com.thaishopfun.oms.stock.StockIdempotency.Outcome;
import com.thaishopfun.oms.stock.StockIdempotency.Stored;
import com.thaishopfun.oms.stock.StockRepository.InventoryRow;
import com.thaishopfun.oms.stock.StockRepository.LedgerEntry;
import com.thaishopfun.oms.stock.StockRepository.ReservationRow;
import com.thaishopfun.oms.stock.StockRepository.SkuInfo;
import com.thaishopfun.oms.stock.StockRepository.SkuWarehouse;
import com.thaishopfun.oms.tenant.TenantContext;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/**
 * Holds stock for checkouts and orders. Plain JDBC on the V4 tables, no HTTP (T12A adds 4.3).
 *
 * <p>Lock order, the same for every write: the idempotency key row, then at most one owner advisory
 * lock (reserve, transfer target, and owner-targeted release/consume/unpack), then inventory rows
 * in ascending {@code inventory.id}, then reservation rows. Status is re-checked after the
 * reservation rows are locked, because another transaction or the expiry job may have moved them.
 *
 * <p>Every call runs at READ COMMITTED inside the {@link TenantContext} tenant. With no transaction
 * open, the engine opens its own and retries it whole on deadlock or serialization failure. Inside
 * a caller's transaction it joins without retry. Business failures other than {@code OUT_OF_STOCK}
 * throw {@link StockOperationException}; like {@code OUT_OF_STOCK}, they are stored under the key
 * first, so a replay gives the same answer.
 */
@Service
public class ReservationEngine {

  static final String SCOPE_RESERVE = "stock.reserve";
  static final String SCOPE_TRANSFER = "stock.transfer";
  static final String SCOPE_RELEASE = "stock.release";
  static final String SCOPE_CONSUME = "stock.consume";
  static final String SCOPE_UNPACK = "stock.unpack";

  static final String EXPIRY_ACTOR = "system:expiry";

  private static final int MAX_ITEMS = 500;

  private final StockTransactions transactions;
  private final StockIdempotency idempotency;
  private final StockRepository repository;
  private final StockHooks hooks;
  private final StockProperties properties;
  private final ApplicationEventPublisher events;
  private final Clock clock;

  ReservationEngine(
      StockTransactions transactions,
      StockIdempotency idempotency,
      StockRepository repository,
      StockHooks hooks,
      StockProperties properties,
      ApplicationEventPublisher events,
      Clock clock) {
    this.transactions = transactions;
    this.idempotency = idempotency;
    this.repository = repository;
    this.hooks = hooks;
    this.properties = properties;
    this.events = events;
    this.clock = clock;
  }

  /** {@link #reserve(StockOwner, List, String, Duration)} with the default TTL. */
  public ReserveResult reserve(StockOwner owner, List<ReserveItem> items, String idempotencyKey) {
    return reserve(owner, items, idempotencyKey, null);
  }

  /**
   * All-or-nothing hold. Bundles are exploded into components and summed per component SKU. One row
   * per component, one shared {@code reservation_group_id}. CHECKOUT expires after {@code ttl}
   * (default {@code oms.stock.checkout-ttl}, 15 min). ORDER never expires and takes no TTL.
   */
  public ReserveResult reserve(
      StockOwner owner, List<ReserveItem> items, String idempotencyKey, Duration ttl) {
    // Step 1: Validate before any transaction. These are caller bugs, not stored outcomes.
    requireOwner(owner);
    String key = StockIdempotency.requireKey(idempotencyKey);
    if (items == null || items.isEmpty() || items.size() > MAX_ITEMS) {
      throw new IllegalArgumentException("items must hold 1.." + MAX_ITEMS + " entries");
    }
    if (owner.type() == OwnerType.ORDER && ttl != null) {
      throw new IllegalArgumentException("an ORDER reservation does not expire");
    }
    if (ttl != null && (ttl.isZero() || ttl.isNegative())) {
      throw new IllegalArgumentException("ttl must be positive");
    }
    List<ReserveItem> sorted = new ArrayList<>(items);
    sorted.sort(
        Comparator.comparing((ReserveItem item) -> item.skuId().toString())
            .thenComparing(item -> String.valueOf(item.warehouseId()))
            .thenComparingInt(ReserveItem::qty));
    String hash = StockIdempotency.sha256(canonicalReserve(owner, ttl, sorted));

    // Step 2: One transaction: key, owner lock, explode, lock inventory, conditional UPDATE.
    Outcome<ReserveResult> outcome =
        transactions.write(SCOPE_RESERVE, () -> doReserve(owner, sorted, key, hash, ttl));
    return outcome.unwrap();
  }

  /**
   * Commits a checkout into an order: owner becomes {@code ORDER order_ref}, {@code expires_at} is
   * cleared. {@code reserved} and the ledger are unchanged. A group that is no longer ACTIVE, or is
   * past its {@code expires_at}, fails with {@code RESERVATION_NOT_ACTIVE} (T12 then re-reserves).
   */
  public TransferResult transferOwner(
      UUID reservationGroupId, String orderRef, String idempotencyKey) {
    if (reservationGroupId == null) {
      throw new IllegalArgumentException("reservationGroupId is required");
    }
    StockOwner target = StockOwner.order(orderRef);
    String key = StockIdempotency.requireKey(idempotencyKey);
    String hash =
        StockIdempotency.sha256("transfer|" + reservationGroupId + "|" + ownerKey(target));
    Outcome<TransferResult> outcome =
        transactions.write(SCOPE_TRANSFER, () -> doTransfer(reservationGroupId, target, key, hash));
    return outcome.unwrap();
  }

  /** ACTIVE rows of the group become RELEASED. Nothing ACTIVE left is still a success (4.3 204). */
  public ReservationChange release(UUID reservationGroupId, String idempotencyKey) {
    if (reservationGroupId == null) {
      throw new IllegalArgumentException("reservationGroupId is required");
    }
    String key = StockIdempotency.requireKey(idempotencyKey);
    String hash = StockIdempotency.sha256("release|group|" + reservationGroupId);
    Outcome<ReservationChange> outcome =
        transactions.write(
            SCOPE_RELEASE,
            () -> doSettle(SCOPE_RELEASE, key, hash, null, reservationGroupId, Settle.RELEASE));
    return outcome.unwrap();
  }

  /** Every ACTIVE row of the owner becomes RELEASED. Nothing ACTIVE left is still a success. */
  public ReservationChange release(StockOwner owner, String idempotencyKey) {
    requireOwner(owner);
    String key = StockIdempotency.requireKey(idempotencyKey);
    String hash = StockIdempotency.sha256("release|owner|" + ownerKey(owner));
    Outcome<ReservationChange> outcome =
        transactions.write(
            SCOPE_RELEASE, () -> doSettle(SCOPE_RELEASE, key, hash, owner, null, Settle.RELEASE));
    return outcome.unwrap();
  }

  /**
   * Ship. ACTIVE ORDER rows become CONSUMED; {@code on_hand} and {@code reserved} drop together in
   * one conditional UPDATE; ledger {@code SHIP}. No ACTIVE rows is {@code RESERVATION_NOT_ACTIVE}.
   */
  public ReservationChange consume(StockOwner owner, String idempotencyKey) {
    requireOrderOwner(owner, "consume");
    String key = StockIdempotency.requireKey(idempotencyKey);
    String hash = StockIdempotency.sha256("consume|" + ownerKey(owner));
    Outcome<ReservationChange> outcome =
        transactions.write(
            SCOPE_CONSUME, () -> doSettle(SCOPE_CONSUME, key, hash, owner, null, Settle.CONSUME));
    return outcome.unwrap();
  }

  /**
   * Cancel after packing, before ship. ACTIVE ORDER rows become RELEASED with ledger reason {@code
   * UNPACK} (release deltas). No ACTIVE rows is {@code RESERVATION_NOT_ACTIVE}.
   */
  public ReservationChange unpack(StockOwner owner, String idempotencyKey) {
    requireOrderOwner(owner, "unpack");
    String key = StockIdempotency.requireKey(idempotencyKey);
    String hash = StockIdempotency.sha256("unpack|" + ownerKey(owner));
    Outcome<ReservationChange> outcome =
        transactions.write(
            SCOPE_UNPACK, () -> doSettle(SCOPE_UNPACK, key, hash, owner, null, Settle.UNPACK));
    return outcome.unwrap();
  }

  /**
   * One expiry batch for the current tenant: ACTIVE rows with {@code expires_at <= now} become
   * EXPIRED, {@code reserved -= qty}, ledger {@code RELEASE}. Returns how many candidates were
   * read, so the job knows whether another batch may be waiting.
   */
  ExpiryBatch expireBatch(Instant now, int limit) {
    return transactions.write("stock.expire", () -> doExpire(now, limit));
  }

  record ExpiryBatch(int candidates, int expired) {}

  // ---- reserve -----------------------------------------------------------------------------

  private Outcome<ReserveResult> doReserve(
      StockOwner owner, List<ReserveItem> items, String key, String hash, Duration ttl) {
    UUID tenantId = TenantContext.requireTenantId();
    // Step 1: Idempotency first. A replay returns the stored answer without touching stock.
    Stored stored = idempotency.claim(tenantId, SCOPE_RESERVE, key, hash);
    if (stored != null) {
      return idempotency.replay(stored, ReserveResult.class);
    }

    // Step 2: One ACTIVE group per owner. The owner lock makes this check race-free.
    repository.lockOwner(tenantId, owner);
    if (repository.ownerHasActive(owner)) {
      return fail(
          tenantId,
          SCOPE_RESERVE,
          key,
          StockError.OWNER_ALREADY_RESERVED,
          "owner " + ownerString(owner) + " already has an ACTIVE reservation");
    }

    // Step 3: Resolve the default warehouse only if some item needs it.
    UUID defaultWarehouse = null;
    if (items.stream().anyMatch(item -> item.warehouseId() == null)) {
      defaultWarehouse = repository.defaultWarehouse();
      if (defaultWarehouse == null) {
        return fail(
            tenantId,
            SCOPE_RESERVE,
            key,
            StockError.NO_DEFAULT_WAREHOUSE,
            "tenant has no default warehouse");
      }
    }

    // Step 4: Explode bundles and sum per component SKU and warehouse.
    Set<UUID> itemSkuIds = new LinkedHashSet<>();
    items.forEach(item -> itemSkuIds.add(item.skuId()));
    Map<UUID, SkuInfo> skus = repository.skus(itemSkuIds);
    for (UUID skuId : itemSkuIds) {
      SkuInfo sku = skus.get(skuId);
      if (sku == null) {
        return fail(tenantId, SCOPE_RESERVE, key, StockError.UNKNOWN_SKU, "unknown sku " + skuId);
      }
      if (sku.bundle() && sku.components().isEmpty()) {
        return fail(
            tenantId,
            SCOPE_RESERVE,
            key,
            StockError.BUNDLE_WITHOUT_COMPONENTS,
            "bundle " + skuId + " has no components");
      }
    }
    Map<SkuWarehouse, Need> needs = explode(items, skus, defaultWarehouse);

    // Step 5: Lock the inventory rows in id order, then check every component under the lock.
    Map<SkuWarehouse, InventoryRow> inventory = repository.lockInventory(needs.keySet());
    hooks.afterInventoryLocked(SCOPE_RESERVE);
    List<Shortfall> shortfalls = new ArrayList<>();
    for (Map.Entry<SkuWarehouse, Need> entry : needs.entrySet()) {
      InventoryRow row = inventory.get(entry.getKey());
      int available = row == null ? 0 : row.available();
      Need need = entry.getValue();
      if (available < need.qty) {
        shortfalls.add(
            new Shortfall(
                entry.getKey().skuId(),
                entry.getKey().warehouseId(),
                need.qty,
                Math.max(available, 0),
                List.copyOf(need.requestedBy)));
      }
    }
    if (!shortfalls.isEmpty()) {
      // Step 6: All-or-nothing. Nothing was written; the answer is stored for replays.
      ReserveResult result = ReserveResult.outOfStock(owner, shortfalls);
      idempotency.complete(tenantId, SCOPE_RESERVE, key, StockError.OUT_OF_STOCK.status(), result);
      return Outcome.success(result);
    }

    // Step 7: Conditional UPDATE. Under the lock it cannot miss; if it does, retry the whole tx.
    Map<UUID, Integer> byInventory = new LinkedHashMap<>();
    for (Map.Entry<SkuWarehouse, Need> entry : needs.entrySet()) {
      byInventory.merge(inventory.get(entry.getKey()).id(), entry.getValue().qty, Integer::sum);
    }
    int updated = repository.reserveInventory(byInventory);
    if (updated != byInventory.size()) {
      throw new StockConflictException(
          "reserve updated " + updated + " of " + byInventory.size() + " inventory rows");
    }

    // Step 8: One reservation row and one RESERVE ledger row per component.
    UUID groupId = UuidV7.generate();
    Instant expiresAt =
        owner.type() == OwnerType.CHECKOUT
            ? clock
                .instant()
                .plus(ttl == null ? properties.getCheckoutTtl() : ttl)
                .truncatedTo(ChronoUnit.MICROS)
            : null;
    List<ReservedLine> lines = new ArrayList<>();
    List<LedgerEntry> ledger = new ArrayList<>();
    for (Map.Entry<SkuWarehouse, Need> entry : needs.entrySet()) {
      ReservedLine line =
          new ReservedLine(
              UuidV7.generate(),
              entry.getKey().skuId(),
              entry.getKey().warehouseId(),
              entry.getValue().qty);
      lines.add(line);
      ledger.add(
          new LedgerEntry(
              UuidV7.generate(),
              line.skuId(),
              line.warehouseId(),
              0,
              line.qty(),
              line.reservationId()));
    }
    repository.insertReservations(tenantId, owner, groupId, expiresAt, lines);
    repository.insertLedger(tenantId, "RESERVE", actor(), ledger);

    // Step 9: Tell listeners after commit, then store the answer.
    publishChanged(tenantId, lines);
    ReserveResult result = ReserveResult.success(groupId, owner, expiresAt, List.copyOf(lines));
    idempotency.complete(tenantId, SCOPE_RESERVE, key, 201, result);
    return Outcome.success(result);
  }

  private static Map<SkuWarehouse, Need> explode(
      List<ReserveItem> items, Map<UUID, SkuInfo> skus, UUID defaultWarehouse) {
    Map<SkuWarehouse, Need> needs = new LinkedHashMap<>();
    Map<UUID, UUID> warehouseBySku = new HashMap<>();
    for (ReserveItem item : items) {
      UUID warehouseId = item.warehouseId() == null ? defaultWarehouse : item.warehouseId();
      SkuInfo sku = skus.get(item.skuId());
      List<StockRepository.Component> parts =
          sku.bundle() ? sku.components() : List.of(new StockRepository.Component(sku.id(), 1));
      for (StockRepository.Component part : parts) {
        // Step 1: The ACTIVE unique index is per (owner, sku), so one warehouse per component.
        UUID previous = warehouseBySku.putIfAbsent(part.skuId(), warehouseId);
        if (previous != null && !previous.equals(warehouseId)) {
          throw new IllegalArgumentException(
              "sku " + part.skuId() + " is requested from two warehouses");
        }
        int qty;
        try {
          qty = Math.multiplyExact(item.qty(), part.qty());
        } catch (ArithmeticException ex) {
          throw new IllegalArgumentException("quantity is too large", ex);
        }
        Need need =
            needs.computeIfAbsent(
                new SkuWarehouse(part.skuId(), warehouseId), ignored -> new Need());
        try {
          need.qty = Math.addExact(need.qty, qty);
        } catch (ArithmeticException ex) {
          throw new IllegalArgumentException("quantity is too large", ex);
        }
        need.requestedBy.add(item.skuId());
      }
    }
    return needs;
  }

  private static final class Need {
    private int qty;
    private final Set<UUID> requestedBy = new LinkedHashSet<>();
  }

  // ---- transfer ----------------------------------------------------------------------------

  private Outcome<TransferResult> doTransfer(
      UUID groupId, StockOwner target, String key, String hash) {
    UUID tenantId = TenantContext.requireTenantId();
    // Step 1: Idempotency first.
    Stored stored = idempotency.claim(tenantId, SCOPE_TRANSFER, key, hash);
    if (stored != null) {
      return idempotency.replay(stored, TransferResult.class);
    }
    // Step 2: Find the group. Unlocked read, only to learn which rows to lock.
    List<ReservationRow> group = repository.reservationsByGroup(groupId);
    if (group.isEmpty()) {
      return fail(
          tenantId,
          SCOPE_TRANSFER,
          key,
          StockError.RESERVATION_NOT_FOUND,
          "reservation " + groupId + " does not exist");
    }

    // Step 3: Owner lock on the target, then inventory in id order, then the group's rows.
    repository.lockOwner(tenantId, target);
    Map<SkuWarehouse, InventoryRow> inventory = repository.lockInventory(keys(group));
    hooks.afterInventoryLocked(SCOPE_TRANSFER);
    List<ReservationRow> locked = repository.lockReservations(ids(group));

    // Step 4: Re-check under the lock. Already ours = done. Anything else not ACTIVE = refuse.
    List<ReservationRow> active = locked.stream().filter(ReservationRow::active).toList();
    if (active.isEmpty()) {
      return fail(
          tenantId,
          SCOPE_TRANSFER,
          key,
          StockError.RESERVATION_NOT_ACTIVE,
          "reservation " + groupId + " is not ACTIVE");
    }
    if (active.stream().allMatch(row -> row.ownedBy(target))) {
      TransferResult result = new TransferResult(groupId, target, lines(active));
      idempotency.complete(tenantId, SCOPE_TRANSFER, key, 200, result);
      return Outcome.success(result);
    }
    if (active.stream().anyMatch(row -> row.ownerType() != OwnerType.CHECKOUT)) {
      return fail(
          tenantId,
          SCOPE_TRANSFER,
          key,
          StockError.RESERVATION_NOT_ACTIVE,
          "reservation " + groupId + " belongs to another order");
    }
    // Step 5: Past expires_at but not swept yet. Expire it now, so T12's re-reserve sees the stock.
    Instant now = clock.instant();
    if (active.stream().anyMatch(row -> row.expiredAt(now))) {
      settle(tenantId, active, inventory, Settle.EXPIRE, EXPIRY_ACTOR);
      return fail(
          tenantId,
          SCOPE_TRANSFER,
          key,
          StockError.RESERVATION_NOT_ACTIVE,
          "reservation " + groupId + " expired");
    }
    if (repository.ownerHasActive(target)) {
      return fail(
          tenantId,
          SCOPE_TRANSFER,
          key,
          StockError.OWNER_ALREADY_RESERVED,
          "owner " + ownerString(target) + " already has an ACTIVE reservation");
    }

    // Step 6: Owner and expiry change only. No inventory delta, no ledger row, no StockChanged.
    int moved = repository.transferOwner(ids(active), target);
    if (moved != active.size()) {
      throw new StockConflictException("transfer moved " + moved + " of " + active.size());
    }
    TransferResult result = new TransferResult(groupId, target, lines(active));
    idempotency.complete(tenantId, SCOPE_TRANSFER, key, 200, result);
    return Outcome.success(result);
  }

  // ---- release / consume / unpack ----------------------------------------------------------

  enum Settle {
    RELEASE("RELEASED", "RELEASE", false),
    UNPACK("RELEASED", "UNPACK", false),
    CONSUME("CONSUMED", "SHIP", true),
    EXPIRE("EXPIRED", "RELEASE", false);

    final String status;
    final String reason;
    final boolean ship;

    Settle(String status, String reason, boolean ship) {
      this.status = status;
      this.reason = reason;
      this.ship = ship;
    }
  }

  private Outcome<ReservationChange> doSettle(
      String scope, String key, String hash, StockOwner owner, UUID groupId, Settle settle) {
    UUID tenantId = TenantContext.requireTenantId();
    // Step 1: Idempotency first.
    Stored stored = idempotency.claim(tenantId, scope, key, hash);
    if (stored != null) {
      return idempotency.replay(stored, ReservationChange.class);
    }
    // Step 2: Candidates. An owner target takes the owner lock, so a reserve cannot slip in.
    List<ReservationRow> candidates;
    if (owner != null) {
      repository.lockOwner(tenantId, owner);
      candidates = repository.activeByOwner(owner);
    } else {
      candidates = repository.activeByGroup(groupId);
    }
    // Step 3: Inventory in id order, then the reservation rows. Re-check under the lock.
    Map<SkuWarehouse, InventoryRow> inventory =
        candidates.isEmpty() ? Map.of() : repository.lockInventory(keys(candidates));
    if (!candidates.isEmpty()) {
      hooks.afterInventoryLocked(scope);
    }
    List<ReservationRow> kept =
        repository.lockReservations(ids(candidates)).stream()
            .filter(ReservationRow::active)
            .filter(row -> owner == null || row.ownedBy(owner))
            .toList();
    if (kept.isEmpty()) {
      // Step 4: Release is idempotent by state (4.3 DELETE -> 204). Ship/unpack need ACTIVE rows.
      if (settle == Settle.RELEASE) {
        ReservationChange none = new ReservationChange(List.of());
        idempotency.complete(tenantId, scope, key, 204, none);
        return Outcome.success(none);
      }
      return fail(
          tenantId,
          scope,
          key,
          StockError.RESERVATION_NOT_ACTIVE,
          "owner " + ownerString(owner) + " has no ACTIVE reservation");
    }
    // Step 5: Status, inventory, ledger, event, stored answer. All in this transaction.
    settle(tenantId, kept, inventory, settle, actor());
    ReservationChange change = new ReservationChange(lines(kept));
    idempotency.complete(tenantId, scope, key, settle == Settle.RELEASE ? 204 : 200, change);
    return Outcome.success(change);
  }

  private ExpiryBatch doExpire(Instant now, int limit) {
    UUID tenantId = TenantContext.requireTenantId();
    // Step 1: Unlocked candidates, oldest expiry first.
    List<ReservationRow> candidates = repository.expiredActive(now, limit);
    if (candidates.isEmpty()) {
      return new ExpiryBatch(0, 0);
    }
    // Step 2: Inventory in id order, then the rows. Another job run or a release may have won.
    Map<SkuWarehouse, InventoryRow> inventory = repository.lockInventory(keys(candidates));
    hooks.afterInventoryLocked("stock.expire");
    List<ReservationRow> kept =
        repository.lockReservations(ids(candidates)).stream()
            .filter(ReservationRow::active)
            .filter(row -> row.expiredAt(now))
            .toList();
    // Step 3: EXPIRED + reserved -= qty + ledger RELEASE.
    if (!kept.isEmpty()) {
      settle(tenantId, kept, inventory, Settle.EXPIRE, EXPIRY_ACTOR);
    }
    return new ExpiryBatch(candidates.size(), kept.size());
  }

  /**
   * Moves locked ACTIVE rows to a terminal status with the matching inventory and ledger deltas.
   */
  private void settle(
      UUID tenantId,
      List<ReservationRow> rows,
      Map<SkuWarehouse, InventoryRow> inventory,
      Settle settle,
      String actor) {
    // Step 1: Status first. Only rows still ACTIVE move; the caller already holds their locks.
    int moved = repository.setStatus(ids(rows), settle.status);
    if (moved != rows.size()) {
      throw new StockConflictException("status moved " + moved + " of " + rows.size());
    }
    // Step 2: One conditional UPDATE per inventory row. A miss means the lock order was broken.
    Map<UUID, Integer> byInventory = new LinkedHashMap<>();
    for (ReservationRow row : rows) {
      InventoryRow stock = inventory.get(row.key());
      if (stock == null) {
        throw new IllegalStateException("reservation " + row.id() + " has no inventory row");
      }
      byInventory.merge(stock.id(), row.qty(), Integer::sum);
    }
    int updated =
        settle.ship
            ? repository.consumeInventory(byInventory)
            : repository.releaseInventory(byInventory);
    if (updated != byInventory.size()) {
      throw new StockConflictException(
          settle.name() + " updated " + updated + " of " + byInventory.size() + " inventory rows");
    }
    // Step 3: Ledger per reservation row. SHIP moves on_hand too.
    List<LedgerEntry> ledger = new ArrayList<>();
    for (ReservationRow row : rows) {
      ledger.add(
          new LedgerEntry(
              UuidV7.generate(),
              row.skuId(),
              row.warehouseId(),
              settle.ship ? -row.qty() : 0,
              -row.qty(),
              row.id()));
    }
    repository.insertLedger(tenantId, settle.reason, actor, ledger);
    publishChanged(tenantId, lines(rows));
  }

  // ---- helpers -----------------------------------------------------------------------------

  private <T> Outcome<T> fail(
      UUID tenantId, String scope, String key, StockError error, String message) {
    idempotency.fail(tenantId, scope, key, error, message);
    return Outcome.failure(error, message);
  }

  private void publishChanged(UUID tenantId, Collection<ReservedLine> lines) {
    Set<UUID> components = new LinkedHashSet<>();
    lines.forEach(line -> components.add(line.skuId()));
    Set<UUID> bundles = repository.bundlesUsing(components);
    events.publishEvent(new StockChanged(tenantId, components, bundles));
  }

  private static List<SkuWarehouse> keys(Collection<ReservationRow> rows) {
    Set<SkuWarehouse> keys = new LinkedHashSet<>();
    rows.forEach(row -> keys.add(row.key()));
    return List.copyOf(keys);
  }

  private static List<UUID> ids(Collection<ReservationRow> rows) {
    return rows.stream().map(ReservationRow::id).toList();
  }

  private static List<ReservedLine> lines(Collection<ReservationRow> rows) {
    return rows.stream().map(ReservationRow::line).toList();
  }

  private static String actor() {
    UUID userId = TenantContext.userId();
    return userId == null ? "system" : "user:" + userId;
  }

  private static String ownerString(StockOwner owner) {
    return owner == null ? "-" : owner.type() + ":" + owner.ref();
  }

  /** Length-prefixed, so a ref containing the separator cannot collide with another request. */
  private static String ownerKey(StockOwner owner) {
    return owner.type() + ":" + owner.ref().length() + ":" + owner.ref();
  }

  private static void requireOwner(StockOwner owner) {
    if (owner == null) {
      throw new IllegalArgumentException("owner is required");
    }
  }

  private static void requireOrderOwner(StockOwner owner, String operation) {
    requireOwner(owner);
    if (owner.type() != OwnerType.ORDER) {
      throw new IllegalArgumentException(operation + " applies to ORDER reservations only");
    }
  }

  private static String canonicalReserve(StockOwner owner, Duration ttl, List<ReserveItem> items) {
    StringJoiner joiner = new StringJoiner("|");
    joiner.add("reserve").add(ownerKey(owner)).add(ttl == null ? "default" : ttl.toString());
    for (ReserveItem item : items) {
      joiner.add(
          item.skuId()
              + ":"
              + (item.warehouseId() == null ? "default" : item.warehouseId())
              + ":"
              + item.qty());
    }
    return joiner.toString();
  }
}
