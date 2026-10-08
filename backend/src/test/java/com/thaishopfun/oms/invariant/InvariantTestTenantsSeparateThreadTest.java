package com.thaishopfun.oms.invariant;

import static org.assertj.core.api.Assertions.assertThat;

import com.thaishopfun.oms.stock.StockFixture;
import com.thaishopfun.oms.stock.StockTestBase;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Regression for {@link InvariantTestTenants} with {@code @Timeout(SEPARATE_THREAD)}. */
@VerifyInvariants(scope = VerifyInvariants.Scope.STOCK_ONLY)
class InvariantTestTenantsSeparateThreadTest extends StockTestBase {

  private UUID expectedTenant;

  @BeforeEach
  void hookDrainObserver() {
    expectedTenant = null;
    VerifyInvariantsExtension.drainObserver =
        drained -> {
          if (expectedTenant != null) {
            assertThat(drained).contains(expectedTenant);
          }
        };
  }

  @AfterEach
  void unhookDrainObserver() {
    VerifyInvariantsExtension.drainObserver = null;
  }

  @Test
  @Timeout(value = 2, unit = TimeUnit.MINUTES, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
  void workerThreadRegistrationIsCheckedAfterTest() {
    StockFixture.Shop shop = fixture.shop("ACTIVE");
    expectedTenant = shop.tenant();
    fixture.sku(shop, 1);
  }
}
