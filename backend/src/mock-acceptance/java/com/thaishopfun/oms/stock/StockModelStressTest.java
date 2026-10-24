package com.thaishopfun.oms.stock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.thaishopfun.oms.invariant.InvariantChecker;
import com.thaishopfun.oms.invariant.InvariantTestTenants;
import com.thaishopfun.oms.invariant.VerifyInvariants;
import com.thaishopfun.oms.stock.StockFixture.Shop;
import com.thaishopfun.oms.stockdoc.StockDocumentLineRequest;
import com.thaishopfun.oms.stockdoc.StockDocumentRequest;
import com.thaishopfun.oms.stockdoc.StockDocumentService;
import com.thaishopfun.oms.tenant.TenantContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.jqwik.api.AfterFailureMode;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.EdgeCasesMode;
import net.jqwik.api.ForAll;
import net.jqwik.api.GenerationMode;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.lifecycle.AfterProperty;
import net.jqwik.api.lifecycle.BeforeProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestContextManager;

/**
 * 100 tries x 100 commands = 10,000 operations in Backend CI. Override with -Djqwik.tries=N. jqwik
 * shrinks command fields; stable SKU/owner indexes make the printed sample replayable. Jupiter
 * extensions do not run under jqwik: bootstrap Spring and check T00 explicitly per try.
 */
@VerifyInvariants(scope = VerifyInvariants.Scope.STOCK_ONLY)
class StockModelStressTest extends StockTestBase {
  @Autowired InvariantChecker checker;
  @Autowired StockDocumentService documents;
  @Autowired StockMovements movements;
  private TestContextManager spring;

  enum Kind {
    RESERVE,
    RELEASE,
    CONSUME,
    EXPIRE,
    TRANSFER,
    RECEIVE,
    ADJUST
  }

  record Op(Kind kind, int owner, int sku, int qty) {}

  record Hold(UUID group, StockOwner owner, int[] quantities) {}

  @BeforeProperty
  void startSpring() throws Exception {
    spring = new TestContextManager(getClass());
    spring.beforeTestClass();
    spring.prepareTestInstance(this);
    var violations = checker.checkSchema();
    assertThat(violations).as(InvariantChecker.formatFailures(violations)).isEmpty();
  }

  @AfterProperty
  void stopSpring() throws Exception {
    if (spring != null) {
      spring.afterTestClass();
    }
  }

  @Provide
  Arbitrary<List<Op>> operations() {
    return Combinators.combine(
            Arbitraries.of(Kind.values()), Arbitraries.integers().between(0, 3),
            Arbitraries.integers().between(0, 4), Arbitraries.integers().between(1, 4))
        .as(Op::new)
        .list()
        .ofSize(100);
  }

  @Property(
      seed = "2026102409",
      afterFailure = AfterFailureMode.PREVIOUS_SEED,
      generation = GenerationMode.RANDOMIZED,
      edgeCases = EdgeCasesMode.NONE)
  void tenThousandRandomOperationsMatchModelAndInvariants(
      @ForAll("operations") List<Op> operations) {
    fixture();
    InvariantTestTenants.clear();
    Shop shop = fixture.shop("ACTIVE");
    double deadlocksBefore = deadlocks();
    try {
      new ModelRun(shop).execute(operations);
    } finally {
      try {
        var violations = checker.checkTenantStock(shop.tenant());
        assertThat(violations).as(InvariantChecker.formatFailures(violations)).isEmpty();
        assertThat(deadlocks() - deadlocksBefore).as("engine SQLSTATE 40P01 occurrences").isZero();
      } finally {
        clearContext();
        InvariantTestTenants.drain();
      }
    }
  }

  private double deadlocks() {
    return counter(StockRetry.RETRY_METRIC, "deadlock")
        + counter(StockRetry.BUSY_METRIC, "deadlock");
  }

  private final class ModelRun {
    private final Shop shop;
    private final List<UUID> skus;
    private final int[] onHand = {12, 9, 15};
    private final int[] reserved = new int[3];
    // Three components, plus two bundles sharing A. No production expansion logic is called.
    private final int[][] recipes = {{1, 0, 0}, {0, 1, 0}, {0, 0, 1}, {2, 1, 0}, {1, 0, 3}};
    private final Hold[] holds = new Hold[4];
    private final List<Op> prefix = new ArrayList<>();
    private int step;

    ModelRun(Shop shop) {
      this.shop = shop;
      assertThat(
              fixture.inTenant(
                  shop.tenant(), () -> jdbc.queryForObject("SELECT current_user", String.class)))
          .isEqualTo("oms_app");
      UUID a = fixture.sku(shop, onHand[0]);
      UUID b = fixture.sku(shop, onHand[1]);
      UUID c = fixture.sku(shop, onHand[2]);
      skus =
          List.of(
              a,
              b,
              c,
              fixture.bundle(shop, Map.of(a, 2, b, 1)),
              fixture.bundle(shop, Map.of(a, 1, c, 3)));
    }

    void execute(List<Op> operations) {
      for (Op op : operations) {
        prefix.add(op);
        try {
          apply(op);
          assertModel();
        } catch (AssertionError | RuntimeException failure) {
          throw new AssertionError(
              "T09 seed=2026102409 step=" + step + " prefix=" + prefix, failure);
        }
        step++;
      }
    }

    void apply(Op op) {
      int slot = op.owner();
      Hold hold = holds[slot];
      switch (op.kind()) {
        case RECEIVE, ADJUST -> post(op);
        case EXPIRE -> {
          clock.advance(properties.getCheckoutTtl().plusSeconds(1));
          expiryJob.runOnce();
          for (int i = 0; i < holds.length; i++) {
            if (holds[i] != null && holds[i].owner().type() == OwnerType.CHECKOUT) {
              settle(i, false);
            }
          }
        }
        case RELEASE -> {
          // An empty slot still exercises the idempotent owner release API.
          StockOwner owner = hold == null ? StockOwner.order("empty-" + slot) : hold.owner();
          as(shop, () -> engine.release(owner, key()));
          if (hold != null) settle(slot, false);
        }
        case TRANSFER -> {
          if (hold == null) reserve(op);
          else if (hold.owner().type() == OwnerType.CHECKOUT) {
            StockOwner order = StockOwner.order("order-" + slot);
            as(shop, () -> engine.transferOwner(hold.group(), order.ref(), key()));
            holds[slot] = new Hold(hold.group(), order, hold.quantities());
          } else {
            as(shop, () -> engine.release(hold.owner(), key()));
            settle(slot, false);
          }
        }
        case CONSUME -> {
          if (hold == null) reserve(op);
          else if (hold.owner().type() == OwnerType.ORDER) {
            as(shop, () -> engine.consume(hold.owner(), key()));
            settle(slot, true);
          } else {
            // Release a checkout; shipment requires a transferred ORDER owner.
            as(shop, () -> engine.release(hold.owner(), key()));
            settle(slot, false);
          }
        }
        case RESERVE -> reserve(op);
      }
    }

    void reserve(Op op) {
      int slot = op.owner();
      if (holds[slot] != null) {
        assertThatThrownBy(
                () ->
                    as(
                        shop,
                        () ->
                            engine.reserve(
                                holds[slot].owner(),
                                List.of(ReserveItem.of(skus.get(op.sku()), op.qty())),
                                key())))
            .isInstanceOf(StockOperationException.class)
            .extracting(error -> ((StockOperationException) error).error())
            .isEqualTo(StockError.OWNER_ALREADY_RESERVED);
        return;
      }
      StockOwner owner =
          slot % 2 == 0
              ? StockOwner.checkout("checkout-" + slot)
              : StockOwner.order("order-" + slot);
      int[] need = new int[3];
      boolean possible = true;
      for (int i = 0; i < 3; i++) {
        need[i] = recipes[op.sku()][i] * op.qty();
        possible &= onHand[i] - reserved[i] >= need[i];
      }
      ReserveResult result =
          as(
              shop,
              () ->
                  engine.reserve(
                      owner, List.of(ReserveItem.of(skus.get(op.sku()), op.qty())), key()));
      assertThat(result.reserved()).as("all-or-nothing reservation").isEqualTo(possible);
      if (possible) {
        for (int i = 0; i < 3; i++) reserved[i] += need[i];
        holds[slot] = new Hold(result.reservationGroupId(), owner, need);
      } else {
        assertThat(result.status()).isEqualTo(ReserveResult.Status.OUT_OF_STOCK);
        assertThat(result.lines()).isEmpty();
      }
    }

    void settle(int slot, boolean ship) {
      for (int i = 0; i < 3; i++) {
        reserved[i] -= holds[slot].quantities()[i];
        if (ship) onHand[i] -= holds[slot].quantities()[i];
      }
      holds[slot] = null;
    }

    void post(Op op) {
      int component = op.sku() % 3;
      boolean receive = op.kind() == Kind.RECEIVE;
      int delta = receive || op.owner() % 2 == 0 ? op.qty() : -op.qty();
      // Real draft APIs and the real document posting engine; never mutate inventory in a fixture.
      TenantContext.set(shop.tenant(), UUID.fromString("00000000-0000-4000-8000-000000000009"));
      try {
        UUID doc =
            documents
                .create(
                    new StockDocumentRequest(
                        receive ? "RECEIVE" : "ADJUSTMENT", null, "T09 step " + step))
                .id();
        documents.addLine(
            doc,
            new StockDocumentLineRequest(
                skus.get(component),
                null,
                shop.warehouse(),
                delta,
                null,
                receive ? null : "DATA_ENTRY"));
        if (onHand[component] + delta < reserved[component]) {
          assertThatThrownBy(() -> movements.post(doc, null))
              .isInstanceOf(StockDocumentException.class)
              .extracting(error -> ((StockDocumentException) error).error())
              .isEqualTo(StockError.BELOW_RESERVED);
        } else {
          assertThat(movements.post(doc, null).status()).isEqualTo("POSTED");
          onHand[component] += delta;
        }
      } finally {
        TenantContext.clear();
      }
    }

    String key() {
      return "model-" + step;
    }

    void assertModel() {
      fixture.inTenant(
          shop.tenant(),
          () -> {
            var rows = jdbc.queryForList("SELECT sku_id, on_hand, reserved FROM inventory");
            assertThat(rows).hasSize(3);
            for (var row : rows) {
              int i = skus.indexOf(row.get("sku_id"));
              assertThat(i).isBetween(0, 2);
              assertThat(row.get("on_hand")).as("on_hand[%s]", i).isEqualTo(onHand[i]);
              assertThat(row.get("reserved")).as("reserved[%s]", i).isEqualTo(reserved[i]);
              assertThat(reserved[i]).isBetween(0, onHand[i]);
            }
            return null;
          });
      Map<UUID, Integer> expected = new LinkedHashMap<>();
      for (int sku = 0; sku < recipes.length; sku++) {
        int available = Integer.MAX_VALUE;
        for (int i = 0; i < 3; i++) {
          if (recipes[sku][i] > 0)
            available = Math.min(available, (onHand[i] - reserved[i]) / recipes[sku][i]);
        }
        expected.put(skus.get(sku), available);
      }
      assertThat(as(shop, () -> availability.available(skus, shop.warehouse())))
          .isEqualTo(expected);
    }
  }
}
