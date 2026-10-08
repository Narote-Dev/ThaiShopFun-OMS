package com.thaishopfun.oms.invariant;

import static org.assertj.core.api.Assertions.assertThat;

import com.thaishopfun.oms.stock.StockFixture;
import com.thaishopfun.oms.stock.StockTestBase;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Regression for {@link InvariantTestTenants} with {@code @Timeout(SEPARATE_THREAD)}. */
@VerifyInvariants(scope = VerifyInvariants.Scope.STOCK_ONLY)
class InvariantTestTenantsSeparateThreadTest extends StockTestBase {

  private static volatile Set<UUID> drainedByExtension;
  private static volatile UUID expectedTenant;

  @BeforeAll
  static void captureExtensionDrain() {
    drainedByExtension = null;
    expectedTenant = null;
    VerifyInvariantsExtension.drainObserver = drained -> drainedByExtension = drained;
  }

  @AfterAll
  static void assertExtensionSawWorkerThreadTenant() {
    VerifyInvariantsExtension.drainObserver = null;
    assertThat(drainedByExtension).isNotNull();
    assertThat(drainedByExtension).contains(expectedTenant);
  }

  @Test
  @Timeout(value = 2, unit = TimeUnit.MINUTES, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
  void workerThreadRegistrationIsCheckedAfterTest() {
    StockFixture.Shop shop = fixture.shop("ACTIVE");
    expectedTenant = shop.tenant();
    fixture.sku(shop, 1);
  }
}
