package com.thaishopfun.oms.stock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.thaishopfun.oms.auth.UuidV7;
import com.thaishopfun.oms.stock.StockFixture.Shop;
import com.thaishopfun.oms.stock.StockTestConfig.Fault;
import com.thaishopfun.oms.stockdoc.StockDocumentLineRequest;
import com.thaishopfun.oms.stockdoc.StockDocumentRequest;
import com.thaishopfun.oms.stockdoc.StockDocumentService;
import com.thaishopfun.oms.tenant.TenantContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * T08A stock documents through the engine layer: every type's ledger reason and deltas, the COUNT
 * rule under concurrent sales, all-or-nothing refusals, idempotent post and void, void reversal,
 * return restock, and posts racing reservations. Runs as {@code oms_app}; drafts are built with the
 * real {@link StockDocumentService}.
 */
class StockMovementsTest extends StockTestBase {

  @Autowired StockMovements movements;
  @Autowired StockDocumentService documents;
  @Autowired com.thaishopfun.oms.stockdoc.StockHistoryService history;

  private final UUID user = UuidV7.generate();

  // ---- each type ---------------------------------------------------------------------------

  @Test
  void everyTypePostsItsReasonWithLineRefsVersionBumpAndStockChanged() {
    Shop shop = fixture.shop("ACTIVE");
    UUID a = fixture.skuWithoutStock(shop);
    UUID bundle = fixture.bundle(shop, Map.of(a, 2));

    // Step 1: OPENING on a SKU with no inventory row: the row is created, then posted into.
    UUID opening = document(shop, "OPENING", null);
    UUID openingLine = line(shop, opening, a, 20, null, null);
    DocumentMovement opened = post(shop, opening);
    assertThat(opened.status()).isEqualTo("POSTED");
    assertThat(opened.postedBy()).isEqualTo(user);
    assertThat(opened.postedAt()).isEqualTo(clock.instant());
    assertThat(fixture.onHand(shop, a)).isEqualTo(20);
    assertThat(stockVersion(shop, a)).isEqualTo(1);
    assertThat(ledgerOf(shop, opening))
        .containsExactly(new Ledger("OPENING_BALANCE", 20, "stock_document_line", openingLine));
    assertThat(status(shop, opening)).isEqualTo("POSTED");
    List<StockChanged> published = events.forTenant(shop.tenant());
    assertThat(published).hasSize(1);
    assertThat(published.get(0).componentSkuIds()).containsExactly(a);
    assertThat(published.get(0).bundleSkuIds()).containsExactly(bundle);

    // Step 2: A second opening balance for the same row is refused, nothing written.
    UUID again = document(shop, "OPENING", null);
    line(shop, again, a, 5, null, null);
    StockDocumentException twice = postFails(shop, again, StockError.OPENING_ALREADY_SET);
    assertThat(twice.problems()).hasSize(1);
    assertThat(status(shop, again)).isEqualTo("DRAFT");

    // Step 3: RECEIVE, ADJUSTMENT (in and out), WRITE_OFF.
    UUID receive = document(shop, "RECEIVE", null);
    UUID receiveLine = line(shop, receive, a, 5, null, null);
    post(shop, receive);
    assertThat(ledgerOf(shop, receive))
        .containsExactly(new Ledger("RECEIVE", 5, "stock_document_line", receiveLine));

    UUID adjust = document(shop, "ADJUSTMENT", null);
    UUID found = line(shop, adjust, a, 3, null, "FOUND");
    UUID lost = line(shop, adjust, a, -2, null, "LOST");
    post(shop, adjust);
    assertThat(ledgerOf(shop, adjust))
        .containsExactlyInAnyOrder(
            new Ledger("ADJUST_IN", 3, "stock_document_line", found),
            new Ledger("ADJUST_OUT", -2, "stock_document_line", lost));

    UUID writeOff = document(shop, "WRITE_OFF", null);
    UUID damaged = line(shop, writeOff, a, 4, null, "DAMAGED");
    post(shop, writeOff);
    assertThat(ledgerOf(shop, writeOff))
        .containsExactly(new Ledger("DAMAGE_WRITE_OFF", -4, "stock_document_line", damaged));

    // Step 4: COUNT: correction = counted - snapshot. 22 on hand, 20 counted.
    UUID count = document(shop, "COUNT", null);
    UUID counted = line(shop, count, a, null, 20, null);
    asUser(shop, () -> documents.startCount(count));
    post(shop, count);
    assertThat(ledgerOf(shop, count))
        .containsExactly(new Ledger("COUNT_CORRECTION", -2, "stock_document_line", counted));
    assertThat(lineQty(shop, counted)).isEqualTo(-2);

    // Step 5: 20 + 5 + 3 - 2 - 4 - 2 = 20. One version bump per posted document, invariants hold.
    assertThat(fixture.onHand(shop, a)).isEqualTo(20);
    assertThat(stockVersion(shop, a)).isEqualTo(5);
    assertThat(events.forTenant(shop.tenant())).hasSize(5);
    fixture.assertInvariants(shop);
  }

  @Test
  void adjustmentNeedsReasonAndOtherNeedsNote() {
    Shop shop = fixture.shop("ACTIVE");
    UUID a = fixture.sku(shop, 10);

    // Step 1: No reason code: 422 REASON_REQUIRED, the draft stays, nothing moves.
    UUID adjust = document(shop, "ADJUSTMENT", null);
    UUID bad = line(shop, adjust, a, -1, null, null);
    StockDocumentException missing = postFails(shop, adjust, StockError.REASON_REQUIRED);
    assertThat(missing.problems()).extracting(LineProblem::lineId).containsExactly(bad);
    assertThat(fixture.onHand(shop, a)).isEqualTo(10);

    // Step 2: OTHER without a note: NOTE_REQUIRED. The key was not kept, so a fixed draft posts.
    asUser(
        shop,
        () ->
            documents.updateLine(
                adjust,
                bad,
                new StockDocumentLineRequest(a, null, shop.warehouse(), -1, null, "OTHER")));
    postFails(shop, adjust, StockError.NOTE_REQUIRED);
    asUser(shop, () -> documents.update(adjust, new StockDocumentRequest(null, "ADJ-1", "Spill")));
    post(shop, adjust);
    assertThat(fixture.onHand(shop, a)).isEqualTo(9);
    assertThat(fixture.ledger(shop, "ADJUST_OUT")).isEqualTo(1);
    fixture.assertInvariants(shop);
  }

  @Test
  void belowReservedIsRefusedForEveryOutgoingTypeAndNothingIsWritten() {
    Shop shop = fixture.shop("ACTIVE");
    UUID free = fixture.sku(shop, 10);
    UUID held = fixture.sku(shop, 5);
    as(
        shop,
        () ->
            engine.reserve(
                StockOwner.order("ord-" + UUID.randomUUID()),
                List.of(ReserveItem.of(held, 4)),
                "key-" + UUID.randomUUID()));
    long ledgerBefore = fixture.ledgerRows(shop);

    // Step 1: ADJUST_OUT: the free line would pass, the held one would not. Neither is applied.
    UUID adjust = document(shop, "ADJUSTMENT", null);
    line(shop, adjust, free, -2, null, "LOST");
    UUID heldLine = line(shop, adjust, held, -2, null, "LOST");
    StockDocumentException adjustOut = postFails(shop, adjust, StockError.BELOW_RESERVED);
    assertThat(adjustOut.problems()).hasSize(1);
    LineProblem problem = adjustOut.problems().get(0);
    assertThat(problem.lineId()).isEqualTo(heldLine);
    assertThat(problem.skuId()).isEqualTo(held);
    assertThat(List.of(problem.onHand(), problem.reserved(), problem.delta()))
        .containsExactly(5, 4, -2);

    // Step 2: WRITE_OFF and COUNT hit the same rule.
    UUID writeOff = document(shop, "WRITE_OFF", null);
    line(shop, writeOff, held, 2, null, null);
    postFails(shop, writeOff, StockError.BELOW_RESERVED);
    UUID count = document(shop, "COUNT", null);
    line(shop, count, held, null, 3, null);
    asUser(shop, () -> documents.startCount(count));
    postFails(shop, count, StockError.BELOW_RESERVED);

    // Step 3: Two lines of one SKU are summed first: -1 and -1 together is below reserved.
    UUID split = document(shop, "ADJUSTMENT", null);
    line(shop, split, held, -1, null, "LOST");
    line(shop, split, held, -1, null, "DAMAGED");
    StockDocumentException summed = postFails(shop, split, StockError.BELOW_RESERVED);
    assertThat(summed.problems().get(0).lineId()).isNull();
    assertThat(summed.problems().get(0).delta()).isEqualTo(-2);

    // Step 4: Nothing moved and every draft is still a draft.
    assertThat(fixture.onHand(shop, free)).isEqualTo(10);
    assertThat(fixture.onHand(shop, held)).isEqualTo(5);
    assertThat(fixture.ledgerRows(shop)).isEqualTo(ledgerBefore);
    assertThat(List.of(status(shop, adjust), status(shop, writeOff), status(shop, count)))
        .containsOnly("DRAFT");
    fixture.assertInvariants(shop);
  }

  // ---- count during sales -------------------------------------------------------------------

  @Test
  void countWithSalesDuringCountingEndsAtCountedMinusShippedSinceStart() {
    Shop shop = fixture.shop("ACTIVE");
    UUID a = fixture.sku(shop, 10);

    // Step 1: Start the count: the line snapshots on_hand = 10.
    UUID count = document(shop, "COUNT", "Aisle 3");
    UUID countLine = line(shop, count, a, null, null, null);
    asUser(shop, () -> documents.startCount(count));
    assertThat(snapshot(shop, countLine)).isEqualTo(10);

    // Step 2: While counting, 3 units are reserved and shipped, 1 more is reserved only.
    StockOwner shipped = StockOwner.order("ord-" + UUID.randomUUID());
    as(shop, () -> engine.reserve(shipped, List.of(ReserveItem.of(a, 3)), key()));
    as(shop, () -> engine.consume(shipped, key()));
    as(
        shop,
        () ->
            engine.reserve(
                StockOwner.order("ord-" + UUID.randomUUID()),
                List.of(ReserveItem.of(a, 1)),
                key()));
    assertThat(fixture.onHand(shop, a)).isEqualTo(7);

    // Step 3: The counter found 9 on the shelf (one lost). The unshipped unit is still there.
    asUser(
        shop,
        () ->
            documents.updateLine(
                count,
                countLine,
                new StockDocumentLineRequest(a, null, shop.warehouse(), null, 9, null)));
    post(shop, count);

    // Step 4: 9 - 10 = -1 on the current 7: final 6 = counted 9 - 3 shipped. Reserved untouched.
    assertThat(fixture.onHand(shop, a)).isEqualTo(6);
    assertThat(fixture.reserved(shop, a)).isEqualTo(1);
    assertThat(ledgerOf(shop, count))
        .containsExactly(new Ledger("COUNT_CORRECTION", -1, "stock_document_line", countLine));
    assertThat(lineQty(shop, countLine)).isEqualTo(-1);
    fixture.assertInvariants(shop);
  }

  @Test
  void zeroCountCorrectionWritesNoLedgerRowAndLinesAddedLaterSnapshotAtInsert() {
    Shop shop = fixture.shop("ACTIVE");
    UUID a = fixture.sku(shop, 4);
    UUID b = fixture.skuWithoutStock(shop);
    UUID count = document(shop, "COUNT", null);
    asUser(shop, () -> documents.startCount(count));

    // Step 1: Added after the start: snapshot at insert (4, and 0 for a SKU with no row).
    UUID lineA = line(shop, count, a, null, 4, null);
    UUID lineB = line(shop, count, b, null, 2, null);
    assertThat(snapshot(shop, lineA)).isEqualTo(4);
    assertThat(snapshot(shop, lineB)).isZero();

    // Step 2: A is unchanged (no entry); B gains 2 on a row created for the post.
    DocumentMovement result = post(shop, count);
    assertThat(result.movements())
        .extracting(DocumentMovement.Movement::lineId)
        .containsExactly(lineB);
    assertThat(fixture.onHand(shop, b)).isEqualTo(2);
    assertThat(lineQty(shop, lineA)).isZero();
    fixture.assertInvariants(shop);
  }

  // ---- idempotency, void -------------------------------------------------------------------

  @Test
  @Timeout(value = 2, unit = TimeUnit.MINUTES)
  void concurrentAndRepeatedPostAndVoidWriteOnceAndReturnTheSameBody() throws Exception {
    Shop shop = fixture.shop("ACTIVE");
    UUID a = fixture.sku(shop, 10);
    UUID b = fixture.sku(shop, 10);
    UUID receive = document(shop, "RECEIVE", null);
    line(shop, receive, a, 5, null, null);
    line(shop, receive, b, 7, null, null);

    // Step 1: Two posts at once (a double click), then a third later: one set of ledger rows.
    List<Callable<DocumentMovement>> posts = new ArrayList<>();
    for (int i = 0; i < 2; i++) {
      posts.add(() -> post(shop, receive));
    }
    List<DocumentMovement> results = runTogether(posts, 2);
    DocumentMovement third = post(shop, receive);
    assertThat(results.get(0)).isEqualTo(results.get(1)).isEqualTo(third);
    assertThat(ledgerOf(shop, receive)).hasSize(2);
    assertThat(fixture.onHand(shop, a)).isEqualTo(15);
    assertThat(fixture.onHand(shop, b)).isEqualTo(17);

    // Step 2: Same for void: reversed exactly once, same body every time.
    List<Callable<DocumentMovement>> voids = new ArrayList<>();
    for (int i = 0; i < 2; i++) {
      voids.add(() -> asUser(shop, () -> movements.voidDocument(receive, null)));
    }
    List<DocumentMovement> voided = runTogether(voids, 2);
    DocumentMovement voidAgain = asUser(shop, () -> movements.voidDocument(receive, null));
    assertThat(voided.get(0)).isEqualTo(voided.get(1)).isEqualTo(voidAgain);
    assertThat(voidAgain.status()).isEqualTo("VOID");
    assertThat(ledgerOf(shop, receive)).hasSize(4);
    assertThat(fixture.onHand(shop, a)).isEqualTo(10);
    assertThat(fixture.onHand(shop, b)).isEqualTo(10);

    // Step 3: Posting a VOID document is DOCUMENT_NOT_DRAFT, even though the post key exists.
    postFails(shop, receive, StockError.DOCUMENT_NOT_DRAFT);
    fixture.assertInvariants(shop);
  }

  @Test
  void voidReversesExactlyAndIsBlockedByReservations() {
    Shop shop = fixture.shop("ACTIVE");
    UUID a = fixture.sku(shop, 0);
    UUID b = fixture.sku(shop, 2);

    // Step 1: Void before post is refused.
    UUID adjust = document(shop, "ADJUSTMENT", null);
    UUID inLine = line(shop, adjust, a, 8, null, "FOUND");
    UUID outLine = line(shop, adjust, b, -2, null, "DATA_ENTRY");
    assertThatThrownBy(() -> asUser(shop, () -> movements.voidDocument(adjust, null)))
        .isInstanceOf(StockDocumentException.class)
        .extracting(ex -> ((StockDocumentException) ex).error())
        .isEqualTo(StockError.DOCUMENT_NOT_POSTED);
    post(shop, adjust);

    // Step 2: 6 of the 8 found units get reserved: the reversal of +8 would go below reserved.
    StockOwner owner = StockOwner.order("ord-" + UUID.randomUUID());
    as(shop, () -> engine.reserve(owner, List.of(ReserveItem.of(a, 6)), key()));
    StockDocumentException blocked =
        (StockDocumentException)
            catchThrowable(() -> asUser(shop, () -> movements.voidDocument(adjust, null)));
    assertThat(blocked.error()).isEqualTo(StockError.BELOW_RESERVED);
    assertThat(blocked.problems()).extracting(LineProblem::lineId).containsExactly(inLine);
    assertThat(status(shop, adjust)).isEqualTo("POSTED");

    // Step 3: Release, then void: same reasons, negated deltas, same line ids.
    as(shop, () -> engine.release(owner, key()));
    DocumentMovement voided = asUser(shop, () -> movements.voidDocument(adjust, null));
    assertThat(voided.voidedAt()).isNotNull();
    assertThat(ledgerOf(shop, adjust))
        .containsExactlyInAnyOrder(
            new Ledger("ADJUST_IN", 8, "stock_document_line", inLine),
            new Ledger("ADJUST_OUT", -2, "stock_document_line", outLine),
            new Ledger("ADJUST_IN", -8, "stock_document_line", inLine),
            new Ledger("ADJUST_OUT", 2, "stock_document_line", outLine));
    assertThat(fixture.onHand(shop, a)).isZero();
    assertThat(fixture.onHand(shop, b)).isEqualTo(2);
    assertThat(status(shop, adjust)).isEqualTo("VOID");
    fixture.assertInvariants(shop);
  }

  @Test
  void transientFailureInsideThePostIsRetriedWhole() {
    Shop shop = fixture.shop("ACTIVE");
    UUID a = fixture.sku(shop, 1);
    UUID receive = document(shop, "RECEIVE", null);
    line(shop, receive, a, 2, null, null);

    // Step 1: A real 40001 after the inventory lock: the whole post runs again, once.
    faults.failNext(Fault.SERIALIZATION);
    post(shop, receive);
    assertThat(faults.fired()).isPositive();
    assertThat(ledgerOf(shop, receive)).hasSize(1);
    assertThat(fixture.onHand(shop, a)).isEqualTo(3);
    fixture.assertInvariants(shop);
  }

  // ---- transactions ------------------------------------------------------------------------

  @Test
  void postNeedsItsOwnReadCommittedTransaction() {
    Shop shop = fixture.shop("ACTIVE");
    UUID a = fixture.sku(shop, 1);
    UUID receive = document(shop, "RECEIVE", null);
    line(shop, receive, a, 2, null, null);
    TenantContext.set(shop.tenant(), user);
    try {
      // Step 1: REPEATABLE READ caller: READ_COMMITTED_REQUIRED before anything is written.
      TransactionTemplate repeatable = new TransactionTemplate(transactions);
      repeatable.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
      assertThatThrownBy(
              () -> repeatable.executeWithoutResult(status -> movements.post(receive, null)))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining(StockTransactions.READ_COMMITTED_REQUIRED);
      // Step 2: A READ COMMITTED caller is refused too: the post owns its transaction.
      TransactionTemplate caller = new TransactionTemplate(transactions);
      assertThatThrownBy(() -> caller.executeWithoutResult(status -> movements.post(receive, null)))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("owns its transaction");
    } finally {
      TenantContext.clear();
    }
    assertThat(status(shop, receive)).isEqualTo("DRAFT");
    assertThat(fixture.onHand(shop, a)).isEqualTo(1);
  }

  @Test
  void returnRestockStandaloneJoinedAndOneWritePerCallerTransaction() {
    Shop shop = fixture.shop("ACTIVE");
    UUID a = fixture.sku(shop, 1);
    UUID fresh = fixture.skuWithoutStock(shop);
    UUID bundle = fixture.bundle(shop, Map.of(a, 1));
    UUID returnLine = UuidV7.generate();
    String restockKey = key();

    // Step 1: Standalone, then replayed with the same key: one ledger row, same answer.
    RestockResult first =
        as(shop, () -> movements.restockReturn(returnLine, a, null, 2, restockKey));
    RestockResult replay =
        as(shop, () -> movements.restockReturn(returnLine, a, null, 2, restockKey));
    assertThat(replay).isEqualTo(first);
    assertThat(fixture.onHand(shop, a)).isEqualTo(3);
    assertThat(
            fixture.inTenant(
                shop.tenant(),
                () ->
                    jdbc.queryForObject(
                        "SELECT count(*) FROM inventory_ledger WHERE reason = 'RETURN_RESTOCK' "
                            + "AND ref_type = 'return_line' AND ref_id = ?",
                        Long.class,
                        returnLine)))
        .isEqualTo(1);
    assertThat(events.forTenant(shop.tenant()).get(0).bundleSkuIds()).containsExactly(bundle);

    // Step 2: Joined inside a caller's READ COMMITTED transaction; the row is created on the way.
    TransactionTemplate caller = new TransactionTemplate(transactions);
    TenantContext.set(shop.tenant(), null);
    try {
      caller.executeWithoutResult(
          status -> movements.restockReturn(UuidV7.generate(), fresh, null, 4, key()));
      // Step 3: A second engine write in the same caller transaction is refused and rolls back.
      assertThatThrownBy(
              () ->
                  caller.executeWithoutResult(
                      status -> {
                        movements.restockReturn(UuidV7.generate(), a, null, 1, key());
                        movements.restockReturn(UuidV7.generate(), a, null, 1, key());
                      }))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("only one engine write per caller transaction");
    } finally {
      TenantContext.clear();
    }
    assertThat(fixture.onHand(shop, fresh)).isEqualTo(4);
    assertThat(fixture.onHand(shop, a)).isEqualTo(3);
    fixture.assertInvariants(shop);
  }

  // ---- concurrency with the engine ---------------------------------------------------------

  @Test
  @Timeout(value = 4, unit = TimeUnit.MINUTES)
  void postsRacingReservationsAndConsumesInOppositeOrdersNeverDeadlockOrBreakInvariants()
      throws Exception {
    Shop shop = fixture.shop("ACTIVE");
    UUID a = fixture.sku(shop, 5_000);
    UUID b = fixture.sku(shop, 5_000);
    double busyBefore = busyTotal();

    // Step 1: Drafts prepared up front: receives, adjustments, and counts over {A, B} and {B, A}.
    List<UUID> drafts = new ArrayList<>();
    for (int i = 0; i < 30; i++) {
      List<UUID> order = i % 2 == 0 ? List.of(a, b) : List.of(b, a);
      String type = List.of("RECEIVE", "ADJUSTMENT", "COUNT").get(i % 3);
      UUID doc = document(shop, type, null);
      for (UUID sku : order) {
        switch (type) {
          case "RECEIVE" -> line(shop, doc, sku, 3, null, null);
          case "ADJUSTMENT" -> line(shop, doc, sku, i % 2 == 0 ? 2 : -2, null, "DATA_ENTRY");
          default -> line(shop, doc, sku, null, 5_000, null);
        }
      }
      if ("COUNT".equals(type)) {
        asUser(shop, () -> documents.startCount(doc));
      }
      drafts.add(doc);
    }

    // Step 2: Posts and reserve+consume pairs in both SKU orders, all released at once.
    List<Callable<Void>> calls = new ArrayList<>();
    for (UUID doc : drafts) {
      calls.add(
          () -> {
            post(shop, doc);
            return null;
          });
    }
    for (int i = 0; i < 60; i++) {
      List<UUID> order = i % 2 == 0 ? List.of(a, b) : List.of(b, a);
      int n = i;
      calls.add(
          () -> {
            StockOwner owner = StockOwner.order("ord-" + UUID.randomUUID());
            List<ReserveItem> items = order.stream().map(sku -> ReserveItem.of(sku, 1)).toList();
            as(shop, () -> engine.reserve(owner, items, key()));
            if (n % 2 == 0) {
              as(shop, () -> engine.consume(owner, key()));
            }
            return null;
          });
    }
    runTogether(calls, 16);

    // Step 3: Every call returned; no StockBusyException; every draft posted; invariants hold.
    assertThat(busyTotal() - busyBefore).isZero();
    for (UUID doc : drafts) {
      assertThat(status(shop, doc)).isEqualTo("POSTED");
    }
    fixture.assertInvariants(shop);
  }

  @Test
  @Timeout(value = 2, unit = TimeUnit.MINUTES)
  void historyRunningTotalsFollowCommitOrderWhenPostsOverlap() throws Exception {
    Shop shop = fixture.shop("ACTIVE");
    UUID sku = fixture.skuWithoutStock(shop);
    UUID slowDoc = document(shop, "RECEIVE", "slow");
    line(shop, slowDoc, sku, 10, null, null);
    UUID fastDoc = document(shop, "RECEIVE", "fast");
    line(shop, fastDoc, sku, 3, null, null);

    // Step 1: The slow post pauses before the inventory lock so the fast post can commit first.
    CountDownLatch slowLocked = new CountDownLatch(1);
    CountDownLatch fastDone = new CountDownLatch(1);
    faults.atNextBeforeInventoryLock(
        () -> {
          slowLocked.countDown();
          try {
            assertThat(fastDone.await(2, TimeUnit.MINUTES)).isTrue();
          } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
          }
        });

    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<?> slow =
          pool.submit(
              () -> {
                TenantContext.set(shop.tenant(), user);
                try {
                  post(shop, slowDoc);
                  return null;
                } finally {
                  TenantContext.clear();
                }
              });
      Future<?> fast =
          pool.submit(
              () -> {
                assertThat(slowLocked.await(2, TimeUnit.MINUTES)).isTrue();
                TenantContext.set(shop.tenant(), user);
                try {
                  post(shop, fastDoc);
                  return null;
                } finally {
                  TenantContext.clear();
                  fastDone.countDown();
                }
              });
      fast.get(2, TimeUnit.MINUTES);
      slow.get(2, TimeUnit.MINUTES);
    } finally {
      pool.shutdownNow();
      faults.reset();
    }

    // Step 2: Commit order is +3 then +10; running totals must match, not transaction start time.
    assertThat(fixture.onHand(shop, sku)).isEqualTo(13);
    var page =
        asUser(shop, () -> history.history(sku, shop.warehouse(), null, null, null, null, 50));
    assertThat(page.items()).hasSize(2);
    assertThat(page.items().get(0).ledgerSeq()).isEqualTo(2);
    assertThat(page.items().get(0).deltaOnHand()).isEqualTo(10);
    assertThat(page.items().get(0).onHandAfter()).isEqualTo(13);
    assertThat(page.items().get(1).ledgerSeq()).isEqualTo(1);
    assertThat(page.items().get(1).deltaOnHand()).isEqualTo(3);
    assertThat(page.items().get(1).onHandAfter()).isEqualTo(3);

    List<Long> createdAtSeqOrder =
        fixture.inTenant(
            shop.tenant(),
            () ->
                jdbc
                    .queryForList(
                        """
                        SELECT ledger_seq FROM inventory_ledger
                        WHERE sku_id = ? AND warehouse_id = ?
                        ORDER BY created_at, id
                        """,
                        sku,
                        shop.warehouse())
                    .stream()
                    .map(row -> ((Number) row.get("ledger_seq")).longValue())
                    .toList());
    assertThat(createdAtSeqOrder).containsExactly(2L, 1L);
    fixture.assertInvariants(shop);
  }

  // ---- helpers -----------------------------------------------------------------------------

  record Ledger(String reason, int deltaOnHand, String refType, UUID refId) {}

  private <T> T asUser(Shop shop, Supplier<T> call) {
    TenantContext.set(shop.tenant(), user);
    try {
      return call.get();
    } finally {
      TenantContext.clear();
    }
  }

  private UUID document(Shop shop, String type, String note) {
    return asUser(shop, () -> documents.create(new StockDocumentRequest(type, null, note)).id());
  }

  private UUID line(Shop shop, UUID doc, UUID sku, Integer qty, Integer counted, String reason) {
    return asUser(
            shop,
            () ->
                documents.addLine(
                    doc,
                    new StockDocumentLineRequest(
                        sku, null, shop.warehouse(), qty, counted, reason)))
        .id();
  }

  private DocumentMovement post(Shop shop, UUID doc) {
    return asUser(shop, () -> movements.post(doc, null));
  }

  private StockDocumentException postFails(Shop shop, UUID doc, StockError error) {
    Throwable thrown = catchThrowable(() -> post(shop, doc));
    assertThat(thrown).isInstanceOf(StockDocumentException.class);
    StockDocumentException ex = (StockDocumentException) thrown;
    assertThat(ex.error()).as(ex.getMessage()).isEqualTo(error);
    return ex;
  }

  private static Throwable catchThrowable(Runnable call) {
    try {
      call.run();
    } catch (Throwable thrown) {
      return thrown;
    }
    throw new AssertionError("expected a failure");
  }

  private List<Ledger> ledgerOf(Shop shop, UUID doc) {
    return fixture.inTenant(
        shop.tenant(),
        () ->
            jdbc.query(
                """
                SELECT reason, delta_on_hand, ref_type, ref_id FROM inventory_ledger
                WHERE ref_type = 'stock_document_line'
                  AND ref_id IN (SELECT id FROM stock_document_line WHERE document_id = ?)
                ORDER BY created_at, id
                """,
                (rs, n) ->
                    new Ledger(
                        rs.getString(1),
                        rs.getInt(2),
                        rs.getString(3),
                        rs.getObject(4, UUID.class)),
                doc));
  }

  private String status(Shop shop, UUID doc) {
    return fixture.inTenant(
        shop.tenant(),
        () ->
            jdbc.queryForObject(
                "SELECT status FROM stock_document WHERE id = ?", String.class, doc));
  }

  private int lineQty(Shop shop, UUID line) {
    return fixture.inTenant(
        shop.tenant(),
        () ->
            jdbc.queryForObject(
                "SELECT qty FROM stock_document_line WHERE id = ?", Integer.class, line));
  }

  private Integer snapshot(Shop shop, UUID line) {
    return fixture.inTenant(
        shop.tenant(),
        () ->
            jdbc.queryForObject(
                "SELECT system_qty_at_start FROM stock_document_line WHERE id = ?",
                Integer.class,
                line));
  }

  private long stockVersion(Shop shop, UUID sku) {
    return fixture.inTenant(
        shop.tenant(),
        () ->
            jdbc.queryForObject(
                "SELECT stock_version FROM inventory WHERE sku_id = ? AND warehouse_id = ?",
                Long.class,
                sku,
                shop.warehouse()));
  }

  private double busyTotal() {
    double total = 0;
    for (StockRetry.Cause cause : StockRetry.Cause.values()) {
      total += counter(StockRetry.BUSY_METRIC, cause.tag);
    }
    return total;
  }

  private static String key() {
    return "key-" + UUID.randomUUID();
  }

  private static <T> List<T> runTogether(List<Callable<T>> calls, int threads) throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    CountDownLatch go = new CountDownLatch(1);
    try {
      List<Future<T>> futures = new ArrayList<>();
      for (Callable<T> call : calls) {
        futures.add(
            pool.submit(
                () -> {
                  go.await();
                  return call.call();
                }));
      }
      go.countDown();
      List<T> results = new ArrayList<>();
      for (Future<T> future : futures) {
        results.add(future.get(3, TimeUnit.MINUTES));
      }
      return results;
    } finally {
      pool.shutdownNow();
    }
  }
}
