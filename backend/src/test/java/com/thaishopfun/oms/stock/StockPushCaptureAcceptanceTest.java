package com.thaishopfun.oms.stock;

import static org.assertj.core.api.Assertions.assertThat;

import com.thaishopfun.oms.invariant.InvariantChecker;
import com.thaishopfun.oms.invariant.VerifyInvariants;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

@VerifyInvariants(scope = VerifyInvariants.Scope.STOCK_ONLY)
class StockPushCaptureAcceptanceTest extends StockTestBase {
  @Autowired InvariantChecker checker;

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void markerAndStockCommitOrRollBackTogether(boolean rollback) {
    StockFixture.Shop shop = fixture.shop("ACTIVE");
    UUID sku = fixture.sku(shop, 10);
    UUID account = fixture.tsfChannelAccount(shop, "ACTIVE", "CONNECTED");
    fixture.channelListing(shop, account, "atomic-capture", sku, true);
    fixture.runInTenant(
        shop.tenant(),
        () -> jdbc.update("UPDATE stock_push_pending SET dirty_since = NULL, changed_at = NULL"));

    as(
        shop,
        () ->
            new TransactionTemplate(transactions)
                .execute(
                    status -> {
                      assertThat(
                              engine
                                  .reserve(
                                      StockOwner.order("atomic-" + UUID.randomUUID()),
                                      List.of(ReserveItem.of(sku, 2)),
                                      "reserve-" + UUID.randomUUID())
                                  .reserved())
                          .isTrue();
                      // BEFORE_COMMIT has not run yet; the marker shares the enclosing stock
                      // transaction.
                      assertThat(
                              jdbc.queryForObject(
                                  "SELECT count(*) FROM stock_push_pending WHERE dirty_since IS NOT NULL",
                                  Long.class))
                          .isZero();
                      if (rollback) status.setRollbackOnly();
                      return null;
                    }));

    assertThat(fixture.reserved(shop, sku)).isEqualTo(rollback ? 0 : 2);
    assertThat(
            fixture.inTenant(
                shop.tenant(),
                () ->
                    jdbc.queryForObject(
                        "SELECT count(*) FROM stock_push_pending WHERE dirty_since IS NOT NULL AND changed_at IS NOT NULL",
                        Long.class)))
        .isEqualTo(rollback ? 0L : 1L);
    fixture.assertInvariants(shop, checker);
  }
}
