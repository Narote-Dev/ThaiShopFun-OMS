package com.thaishopfun.oms.checkout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.thaishopfun.oms.tenant.TenantContext;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

class CheckoutStockControlTest {
  @Test
  @SuppressWarnings("unchecked")
  void controlEnforcesOnlyAllowlistedMappedListingsAndReadsChangesImmediately() {
    UUID tenant = UUID.randomUUID();
    UUID account = UUID.randomUUID();
    UUID sku = UUID.randomUUID();
    var repository = mock(CheckoutRepository.class);
    when(repository.tenantHasDefaultWarehouse(tenant)).thenReturn(true);
    when(repository.listing(tenant, account, "A"))
        .thenReturn(
            Optional.of(new CheckoutRepository.ListingRow(UUID.randomUUID(), sku, true, 0)));
    when(repository.listing(tenant, account, "B"))
        .thenReturn(
            Optional.of(new CheckoutRepository.ListingRow(UUID.randomUUID(), sku, false, 0)));
    var service =
        new CheckoutReserveService(
            repository,
            null,
            null,
            null,
            mock(com.thaishopfun.oms.stock.ReserveDemandPlanner.class),
            null,
            null,
            mock(PlatformTransactionManager.class),
            null,
            Clock.systemUTC(),
            new SimpleMeterRegistry());
    var entitlement = new CheckoutRepository.TenantRow("ACTIVE", null, "shop");
    var control =
        Optional.of(new CheckoutRepository.ChannelAccount(account, "CONTROL", "CONNECTED", false));
    var request =
        new CheckoutReserveService.ReserveRequest(
            "checkout",
            "shop",
            List.of(
                new CheckoutReserveService.RequestItem("A", 1),
                new CheckoutReserveService.RequestItem("B", 1)));
    TenantContext.set(tenant, null);
    try {
      List<CheckoutReserveService.ItemPlan> before =
          ReflectionTestUtils.invokeMethod(service, "planItems", entitlement, control, request);
      assertThat(before)
          .extracting(CheckoutReserveService.ItemPlan::enforced)
          .containsExactly(true, false);
      when(repository.listing(tenant, account, "B"))
          .thenReturn(
              Optional.of(new CheckoutRepository.ListingRow(UUID.randomUUID(), sku, true, 0)));
      List<CheckoutReserveService.ItemPlan> after =
          ReflectionTestUtils.invokeMethod(service, "planItems", entitlement, control, request);
      assertThat(after).allMatch(CheckoutReserveService.ItemPlan::enforced);
      when(repository.listing(tenant, account, "B")).thenReturn(Optional.empty());
      List<CheckoutReserveService.ItemPlan> removed =
          ReflectionTestUtils.invokeMethod(service, "planItems", entitlement, control, request);
      assertThat(removed)
          .extracting(CheckoutReserveService.ItemPlan::enforced)
          .containsExactly(true, false);
    } finally {
      TenantContext.clear();
    }
  }
}
