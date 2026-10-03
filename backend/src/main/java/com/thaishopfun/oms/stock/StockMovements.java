package com.thaishopfun.oms.stock;

import com.thaishopfun.oms.auth.UuidV7;
import com.thaishopfun.oms.stock.StockDocumentStore.DocumentRow;
import com.thaishopfun.oms.stock.StockDocumentStore.LineRow;
import com.thaishopfun.oms.stock.StockDocumentStore.PostedEntry;
import com.thaishopfun.oms.stock.StockIdempotency.Stored;
import com.thaishopfun.oms.stock.StockRepository.InventoryRow;
import com.thaishopfun.oms.stock.StockRepository.LedgerEntry;
import com.thaishopfun.oms.stock.StockRepository.SkuInfo;
import com.thaishopfun.oms.stock.StockRepository.SkuWarehouse;
import com.thaishopfun.oms.tenant.TenantContext;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/**
 * Every {@code on_hand} change that is not a reservation: stock document post and void (T08A) and
 * return restock (T13). Same layer as {@link ReservationEngine}: the same transactions, idempotency
 * keys, repository, conditional UPDATEs, and {@link StockChanged}.
 *
 * <p>Post and void own their transaction ({@link StockTransactions#writeOwned}) and lock, in this
 * order: the {@code idempotency_key} row ({@code stock.document.post} or {@code
 * stock.document.void}, key = document id), the {@code stock_document} row {@code FOR UPDATE} (then
 * its lines are read), then inventory rows in ascending id. Missing inventory rows are created
 * first, in a separate short transaction, so the post itself only locks rows that exist.
 *
 * <p>A business failure ({@link StockDocumentException}) rolls the whole transaction back,
 * idempotency key included, so the draft can be fixed and posted again. A second post of the same
 * document waits on the key, then returns the stored result and writes nothing.
 *
 * <p>Void writes reversing ledger rows on the voided document's own lines (same reason, negated
 * delta), because V4 does not allow a new document to start past DRAFT.
 */
@Service
public class StockMovements {

  static final String SCOPE_POST = "stock.document.post";
  static final String SCOPE_VOID = "stock.document.void";
  static final String SCOPE_RESTOCK = "stock.return.restock";
  static final String SCOPE_PREPARE = "stock.document.prepare";

  public static final int MAX_LINES = 500;
  public static final int MAX_QTY = 1_000_000;

  private static final int MAX_ROW_ATTEMPTS = 3;

  private final StockTransactions transactions;
  private final StockIdempotency idempotency;
  private final StockRepository repository;
  private final StockDocumentStore documents;
  private final StockHooks hooks;
  private final ApplicationEventPublisher events;
  private final Clock clock;

  StockMovements(
      StockTransactions transactions,
      StockIdempotency idempotency,
      StockRepository repository,
      StockDocumentStore documents,
      StockHooks hooks,
      ApplicationEventPublisher events,
      Clock clock) {
    this.transactions = transactions;
    this.idempotency = idempotency;
    this.repository = repository;
    this.documents = documents;
    this.hooks = hooks;
    this.events = events;
    this.clock = clock;
  }

  /**
   * DRAFT to POSTED with every line's inventory and ledger change in one transaction. {@code
   * inTransaction} runs inside it after the stock writes (the caller's audit row); it must not lock
   * stock rows. It is not called when the post is a replay.
   */
  public DocumentMovement post(UUID documentId, Consumer<DocumentMovement> inTransaction) {
    requireDocument(documentId);
    Consumer<DocumentMovement> hook = inTransaction == null ? ignored -> {} : inTransaction;
    for (int attempt = 1; ; attempt++) {
      // Step 1: Missing (sku, warehouse) rows in their own short transaction, before any lock.
      transactions.writeOwned(
          SCOPE_PREPARE,
          () -> {
            repository.ensureInventory(
                TenantContext.requireTenantId(), documents.lineKeys(documentId));
            return null;
          });
      // Step 2: The post. A line added in between may still lack a row: create it and go again.
      try {
        return transactions.writeOwned(SCOPE_POST, () -> doPost(documentId, hook));
      } catch (MissingInventoryException ex) {
        if (attempt >= MAX_ROW_ATTEMPTS) {
          throw new StockBusyException("document lines kept changing", ex);
        }
      }
    }
  }

  /**
   * POSTED to VOID: reversing ledger rows and {@code on_hand} for every entry the post wrote.
   * Refused with {@code BELOW_RESERVED} when a reversal would take a row below {@code reserved} or
   * 0.
   */
  public DocumentMovement voidDocument(UUID documentId, Consumer<DocumentMovement> inTransaction) {
    requireDocument(documentId);
    Consumer<DocumentMovement> hook = inTransaction == null ? ignored -> {} : inTransaction;
    return transactions.writeOwned(SCOPE_VOID, () -> doVoid(documentId, hook));
  }

  /**
   * {@code on_hand += qty} with ledger {@code RETURN_RESTOCK}, {@code ref_type = return_line}.
   * Standalone it owns its transaction; inside a caller's (T13) it joins and is that transaction's
   * one engine write. {@code returnLineId} is opaque here (no foreign key). A null warehouse means
   * the default warehouse.
   */
  public RestockResult restockReturn(
      UUID returnLineId, UUID skuId, UUID warehouseId, int qty, String idempotencyKey) {
    // Step 1: Caller bugs, before any transaction.
    if (returnLineId == null || skuId == null) {
      throw new IllegalArgumentException("returnLineId and skuId are required");
    }
    if (qty < 1 || qty > MAX_QTY) {
      throw new IllegalArgumentException("qty must be between 1 and " + MAX_QTY);
    }
    String key = StockIdempotency.requireKey(idempotencyKey);
    String hash =
        StockIdempotency.sha256(
            "restock|"
                + returnLineId
                + "|"
                + skuId
                + "|"
                + (warehouseId == null ? "default" : warehouseId)
                + "|"
                + qty);
    // Step 2: One engine write: key, row, lock, conditional UPDATE, ledger.
    return transactions.write(
        SCOPE_RESTOCK, () -> doRestock(returnLineId, skuId, warehouseId, qty, key, hash));
  }

  // ---- post --------------------------------------------------------------------------------

  private DocumentMovement doPost(UUID documentId, Consumer<DocumentMovement> hook) {
    UUID tenantId = TenantContext.requireTenantId();
    // Step 1: The key first. A second post waits here, then replays the first one's answer.
    Stored stored =
        idempotency.claim(
            tenantId, SCOPE_POST, documentId.toString(), requestHash("post", documentId));
    if (stored != null) {
      DocumentRow current = documents.find(documentId);
      if (current != null && "VOID".equals(current.status())) {
        throw new StockDocumentException(
            StockError.DOCUMENT_NOT_DRAFT, "document " + documentId + " is VOID");
      }
      return idempotency.replay(stored, DocumentMovement.class).unwrap();
    }

    // Step 2: Lock the document, then read its lines. Line writers hold it FOR SHARE.
    DocumentRow document = documents.lock(documentId);
    if (document == null) {
      throw new StockDocumentException(
          StockError.DOCUMENT_NOT_FOUND, "document " + documentId + " does not exist");
    }
    if (!"DRAFT".equals(document.status())) {
      throw new StockDocumentException(
          StockError.DOCUMENT_NOT_DRAFT,
          "document " + documentId + " is " + document.status() + ", only a DRAFT can be posted");
    }
    List<LineRow> lines = documents.lines(documentId);
    if (lines.isEmpty()) {
      throw new StockDocumentException(StockError.DOCUMENT_EMPTY, "document has no lines");
    }
    if (lines.size() > MAX_LINES) {
      throw new StockDocumentException(
          StockError.INVALID_LINE, "a document holds at most " + MAX_LINES + " lines");
    }

    // Step 3: Per-type rules in Java. Every bad line is reported, and nothing is written.
    List<Planned> planned = plan(document, lines);

    // Step 4: Inventory rows in id order. A row created after Step 1 of post() is not there yet.
    Set<SkuWarehouse> keys = new LinkedHashSet<>();
    planned.forEach(p -> keys.add(p.key()));
    hooks.beforeInventoryLock(SCOPE_POST);
    Map<SkuWarehouse, InventoryRow> inventory = repository.lockInventory(keys);
    hooks.afterInventoryLocked(SCOPE_POST);
    if (!inventory.keySet().containsAll(keys)) {
      throw new MissingInventoryException();
    }

    // Step 5: Opening balance only on a row with no history at all.
    if (document.type() == StockDocumentType.OPENING) {
      Set<SkuWarehouse> used = repository.withLedger(keys);
      if (!used.isEmpty()) {
        List<LineProblem> problems = new ArrayList<>();
        for (Planned p : planned) {
          if (used.contains(p.key())) {
            problems.add(
                LineProblem.of(
                    p.lineId(),
                    p.skuId(),
                    p.warehouseId(),
                    StockError.OPENING_ALREADY_SET,
                    "this SKU already has stock history in this warehouse; use an adjustment"));
          }
        }
        throw new StockDocumentException(
            StockError.OPENING_ALREADY_SET, "opening balance is already set", problems);
      }
    }

    // Step 6: Summed delta per row, checked under the lock, then the conditional UPDATE.
    applyDeltas(inventory, planned);

    // Step 7: COUNT writes the applied correction into qty while the header is still DRAFT.
    if (document.type() == StockDocumentType.COUNT) {
      Map<UUID, Integer> qtyByLine = new LinkedHashMap<>();
      planned.forEach(p -> qtyByLine.put(p.lineId(), p.delta()));
      documents.setLineQty(qtyByLine);
    }

    // Step 8: Ledger per line. A zero count correction writes nothing; a zero opening does, so the
    // row is marked as opened.
    List<Planned> written =
        planned.stream()
            .filter(p -> p.delta() != 0 || document.type() == StockDocumentType.OPENING)
            .toList();
    List<DocumentMovement.Movement> movements = writeLedger(tenantId, written);

    // Step 9: Flip the header last, in the same transaction.
    Instant postedAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
    UUID postedBy = TenantContext.userId();
    if (documents.markPosted(documentId, postedAt, postedBy) != 1) {
      throw new IllegalStateException("document " + documentId + " left DRAFT under its lock");
    }

    // Step 10: Event after commit, caller's audit, then the stored answer.
    publishChanged(tenantId, planned);
    DocumentMovement result =
        new DocumentMovement(
            documentId, document.type(), "POSTED", postedAt, postedBy, null, movements);
    hook.accept(result);
    idempotency.complete(tenantId, SCOPE_POST, documentId.toString(), 200, result);
    return result;
  }

  private List<Planned> plan(DocumentRow document, List<LineRow> lines) {
    StockDocumentType type = document.type();
    // Step 1: A count needs its start snapshot before anything else makes sense.
    if (type == StockDocumentType.COUNT && document.countStartedAt() == null) {
      throw new StockDocumentException(
          StockError.COUNT_NOT_STARTED, "start the count before posting it");
    }
    List<Planned> planned = new ArrayList<>();
    List<LineProblem> problems = new ArrayList<>();
    Set<SkuWarehouse> counted = new HashSet<>();
    for (LineRow line : lines) {
      // Step 2: One rule set per type. Drafts may be incomplete; the post may not.
      LineProblem problem = null;
      String reason = null;
      int delta = 0;
      if (Math.abs((long) line.qty()) > MAX_QTY) {
        problem = problem(line, StockError.INVALID_LINE, "qty must be at most " + MAX_QTY);
      } else {
        switch (type) {
          case OPENING -> {
            if (line.qty() < 0) {
              problem = problem(line, StockError.INVALID_LINE, "opening qty must be 0 or more");
            }
            reason = "OPENING_BALANCE";
            delta = line.qty();
          }
          case RECEIVE -> {
            if (line.qty() <= 0) {
              problem = problem(line, StockError.INVALID_LINE, "received qty must be positive");
            }
            reason = "RECEIVE";
            delta = line.qty();
          }
          case ADJUSTMENT -> {
            AdjustmentReason code = AdjustmentReason.parse(line.reasonCode());
            if (line.reasonCode() == null || line.reasonCode().isBlank()) {
              problem = problem(line, StockError.REASON_REQUIRED, "an adjustment needs a reason");
            } else if (code == null) {
              problem = problem(line, StockError.INVALID_LINE, "unknown adjustment reason");
            } else if (code == AdjustmentReason.OTHER
                && (document.note() == null || document.note().isBlank())) {
              problem =
                  problem(line, StockError.NOTE_REQUIRED, "reason OTHER needs a document note");
            } else if (line.qty() == 0) {
              problem = problem(line, StockError.INVALID_LINE, "adjustment qty must not be 0");
            }
            reason = line.qty() > 0 ? "ADJUST_IN" : "ADJUST_OUT";
            delta = line.qty();
          }
          case WRITE_OFF -> {
            if (line.qty() <= 0) {
              problem = problem(line, StockError.INVALID_LINE, "written-off qty must be positive");
            }
            reason = "DAMAGE_WRITE_OFF";
            delta = -line.qty();
          }
          case COUNT -> {
            // Step 3: correction = counted - snapshot, applied to the current on_hand. Units
            // shipped since the start already left on_hand, so they are not taken twice.
            if (!counted.add(line.key())) {
              problem =
                  problem(
                      line, StockError.DUPLICATE_LINE, "SKU is counted twice in this warehouse");
            } else if (line.systemQtyAtStart() == null) {
              problem = problem(line, StockError.COUNT_NOT_STARTED, "line has no start snapshot");
            } else if (line.countedQty() == null) {
              problem = problem(line, StockError.COUNTED_QTY_REQUIRED, "counted qty is required");
            } else {
              delta = line.countedQty() - line.systemQtyAtStart();
            }
            reason = "COUNT_CORRECTION";
          }
          default -> throw new IllegalStateException("unknown document type " + type);
        }
      }
      if (problem != null) {
        problems.add(problem);
      } else {
        planned.add(new Planned(line.id(), line.skuId(), line.warehouseId(), reason, delta));
      }
    }
    if (!problems.isEmpty()) {
      throw new StockDocumentException(
          problems.get(0).error(), problems.size() + " line(s) cannot be posted", problems);
    }
    return planned;
  }

  // ---- void --------------------------------------------------------------------------------

  private DocumentMovement doVoid(UUID documentId, Consumer<DocumentMovement> hook) {
    UUID tenantId = TenantContext.requireTenantId();
    // Step 1: The key first, like post.
    Stored stored =
        idempotency.claim(
            tenantId, SCOPE_VOID, documentId.toString(), requestHash("void", documentId));
    if (stored != null) {
      return idempotency.replay(stored, DocumentMovement.class).unwrap();
    }
    // Step 2: The document, then the entries its post wrote.
    DocumentRow document = documents.lock(documentId);
    if (document == null) {
      throw new StockDocumentException(
          StockError.DOCUMENT_NOT_FOUND, "document " + documentId + " does not exist");
    }
    if (!"POSTED".equals(document.status())) {
      throw new StockDocumentException(
          StockError.DOCUMENT_NOT_POSTED,
          "document " + documentId + " is " + document.status() + ", only POSTED can be voided");
    }
    List<UUID> lineIds = documents.lines(documentId).stream().map(LineRow::id).toList();
    List<PostedEntry> entries = documents.postedEntries(lineIds);

    // Step 3: Inventory in id order. The ledger foreign key guarantees every row exists.
    List<Planned> reversal = new ArrayList<>();
    for (PostedEntry entry : entries) {
      if (entry.deltaOnHand() != 0) {
        reversal.add(
            new Planned(
                entry.lineId(),
                entry.skuId(),
                entry.warehouseId(),
                entry.reason(),
                -entry.deltaOnHand()));
      }
    }
    Set<SkuWarehouse> keys = new LinkedHashSet<>();
    reversal.forEach(p -> keys.add(p.key()));
    hooks.beforeInventoryLock(SCOPE_VOID);
    Map<SkuWarehouse, InventoryRow> inventory = repository.lockInventory(keys);
    hooks.afterInventoryLocked(SCOPE_VOID);
    if (!inventory.keySet().containsAll(keys)) {
      throw new IllegalStateException("a posted ledger entry has no inventory row");
    }

    // Step 4: Reverse on_hand (refused below reserved), then the negated ledger rows.
    applyDeltas(inventory, reversal);
    List<DocumentMovement.Movement> movements = writeLedger(tenantId, reversal);
    if (documents.markVoid(documentId) != 1) {
      throw new IllegalStateException("document " + documentId + " left POSTED under its lock");
    }

    // Step 5: Event, audit, stored answer.
    publishChanged(tenantId, reversal);
    DocumentMovement result =
        new DocumentMovement(
            documentId,
            document.type(),
            "VOID",
            document.postedAt(),
            document.postedBy(),
            clock.instant().truncatedTo(ChronoUnit.MICROS),
            movements);
    hook.accept(result);
    idempotency.complete(tenantId, SCOPE_VOID, documentId.toString(), 200, result);
    return result;
  }

  // ---- restock -----------------------------------------------------------------------------

  private RestockResult doRestock(
      UUID returnLineId, UUID skuId, UUID warehouseId, int qty, String key, String hash) {
    UUID tenantId = TenantContext.requireTenantId();
    // Step 1: Idempotency first.
    Stored stored = idempotency.claim(tenantId, SCOPE_RESTOCK, key, hash);
    if (stored != null) {
      return idempotency.replay(stored, RestockResult.class).unwrap();
    }
    // Step 2: Resolve the warehouse and the SKU. A bundle has no stock row of its own.
    UUID warehouse = warehouseId == null ? repository.defaultWarehouse() : warehouseId;
    if (warehouse == null) {
      throw new StockOperationException(
          StockError.NO_DEFAULT_WAREHOUSE, "tenant has no default warehouse");
    }
    SkuInfo sku = repository.skus(Set.of(skuId)).get(skuId);
    if (sku == null) {
      throw new StockOperationException(StockError.UNKNOWN_SKU, "unknown sku " + skuId, skuId);
    }
    if (sku.bundle()) {
      throw new StockOperationException(
          StockError.INVALID_LINE, "bundle " + skuId + " has no stock; restock its components");
    }
    // Step 3: One row, so creating it here cannot invert the id order; then lock it.
    SkuWarehouse row = new SkuWarehouse(skuId, warehouse);
    repository.ensureInventory(tenantId, List.of(row));
    hooks.beforeInventoryLock(SCOPE_RESTOCK);
    Map<SkuWarehouse, InventoryRow> inventory = repository.lockInventory(List.of(row));
    hooks.afterInventoryLocked(SCOPE_RESTOCK);
    InventoryRow locked = inventory.get(row);
    if (locked == null) {
      throw new IllegalStateException("inventory row for restock is not visible");
    }
    // Step 4: Conditional UPDATE, ledger, event, stored answer.
    if (repository.adjustOnHand(Map.of(locked.id(), qty)) != 1) {
      throw new StockConflictException("restock updated no inventory row");
    }
    UUID ledgerId = UuidV7.generate();
    repository.insertLedger(
        tenantId,
        "RETURN_RESTOCK",
        StockRepository.REF_RETURN_LINE,
        actor(),
        List.of(new LedgerEntry(ledgerId, skuId, warehouse, qty, 0, returnLineId)));
    events.publishEvent(
        new StockChanged(tenantId, Set.of(skuId), repository.bundlesUsing(Set.of(skuId))));
    RestockResult result = new RestockResult(returnLineId, ledgerId, skuId, warehouse, qty);
    idempotency.complete(tenantId, SCOPE_RESTOCK, key, 200, result);
    return result;
  }

  // ---- shared ------------------------------------------------------------------------------

  /** One planned ledger delta for one document line. */
  private record Planned(UUID lineId, UUID skuId, UUID warehouseId, String reason, int delta) {

    SkuWarehouse key() {
      return new SkuWarehouse(skuId, warehouseId);
    }
  }

  /** Thrown inside the post transaction so it rolls back and {@link #post} creates the row. */
  private static final class MissingInventoryException extends RuntimeException {

    MissingInventoryException() {
      super("an inventory row for a document line does not exist yet");
    }
  }

  private void applyDeltas(Map<SkuWarehouse, InventoryRow> inventory, List<Planned> planned) {
    // Step 1: Sum per row. Several lines for one SKU and warehouse apply as one delta.
    Map<SkuWarehouse, Integer> net = new LinkedHashMap<>();
    Map<SkuWarehouse, List<UUID>> linesByKey = new LinkedHashMap<>();
    for (Planned p : planned) {
      try {
        net.merge(p.key(), p.delta(), Math::addExact);
      } catch (ArithmeticException ex) {
        throw new StockDocumentException(StockError.INVALID_LINE, "quantity is too large");
      }
      linesByKey.computeIfAbsent(p.key(), ignored -> new ArrayList<>()).add(p.lineId());
    }
    // Step 2: Check under the lock. on_hand may never drop below reserved (or 0).
    List<LineProblem> below = new ArrayList<>();
    Map<UUID, Integer> byInventory = new LinkedHashMap<>();
    for (Map.Entry<SkuWarehouse, Integer> entry : net.entrySet()) {
      int delta = entry.getValue();
      if (delta == 0) {
        continue;
      }
      InventoryRow row = inventory.get(entry.getKey());
      long after = (long) row.onHand() + delta;
      if (after < row.reserved() || after < 0 || after > Integer.MAX_VALUE) {
        List<UUID> lineIds = linesByKey.get(entry.getKey());
        below.add(
            new LineProblem(
                lineIds.size() == 1 ? lineIds.get(0) : null,
                row.skuId(),
                row.warehouseId(),
                StockError.BELOW_RESERVED,
                "on_hand "
                    + row.onHand()
                    + " + "
                    + delta
                    + " would be below reserved "
                    + row.reserved(),
                row.onHand(),
                row.reserved(),
                delta));
      } else {
        byInventory.put(row.id(), delta);
      }
    }
    if (!below.isEmpty()) {
      throw new StockDocumentException(
          StockError.BELOW_RESERVED,
          below.size() + " row(s) would go below reserved; release or move orders first",
          below);
    }
    // Step 3: The conditional UPDATE is the guard; the CHECK is the last line of defence.
    if (!byInventory.isEmpty()) {
      int updated = repository.adjustOnHand(byInventory);
      if (updated != byInventory.size()) {
        throw new StockConflictException(
            "document updated " + updated + " of " + byInventory.size() + " inventory rows");
      }
    }
  }

  private List<DocumentMovement.Movement> writeLedger(UUID tenantId, List<Planned> planned) {
    // Step 1: One insert per reason; ref_type stock_document_line, ref_id = line id.
    Map<String, List<LedgerEntry>> byReason = new LinkedHashMap<>();
    List<DocumentMovement.Movement> movements = new ArrayList<>();
    for (Planned p : planned) {
      UUID ledgerId = UuidV7.generate();
      byReason
          .computeIfAbsent(p.reason(), ignored -> new ArrayList<>())
          .add(new LedgerEntry(ledgerId, p.skuId(), p.warehouseId(), p.delta(), 0, p.lineId()));
      movements.add(
          new DocumentMovement.Movement(
              ledgerId, p.lineId(), p.skuId(), p.warehouseId(), p.reason(), p.delta()));
    }
    String actor = actor();
    byReason.forEach(
        (reason, entries) ->
            repository.insertLedger(
                tenantId, reason, StockRepository.REF_DOCUMENT_LINE, actor, entries));
    return List.copyOf(movements);
  }

  private void publishChanged(UUID tenantId, List<Planned> planned) {
    Set<UUID> components = new LinkedHashSet<>();
    planned.stream().filter(p -> p.delta() != 0).forEach(p -> components.add(p.skuId()));
    if (components.isEmpty()) {
      return;
    }
    events.publishEvent(
        new StockChanged(tenantId, components, repository.bundlesUsing(components)));
  }

  private static LineProblem problem(LineRow line, StockError error, String message) {
    return LineProblem.of(line.id(), line.skuId(), line.warehouseId(), error, message);
  }

  private static String requestHash(String operation, UUID documentId) {
    return StockIdempotency.sha256(operation + "|document|" + documentId);
  }

  private static void requireDocument(UUID documentId) {
    if (documentId == null) {
      throw new IllegalArgumentException("documentId is required");
    }
  }

  private static String actor() {
    UUID userId = TenantContext.userId();
    return userId == null ? "system" : "user:" + userId;
  }
}
