package com.thaishopfun.oms.checkout;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class CheckoutRequestHashTest {

  @Test
  void ambiguousListingIdsProduceDifferentHashes() {
    String checkout = "chk-hash";
    String shop = "shop-1";
    CheckoutReserveService.ReserveRequest one =
        new CheckoutReserveService.ReserveRequest(
            checkout, shop, List.of(new CheckoutReserveService.RequestItem("a:1|b", 2)));
    CheckoutReserveService.ReserveRequest two =
        new CheckoutReserveService.ReserveRequest(
            checkout,
            shop,
            List.of(
                new CheckoutReserveService.RequestItem("a", 1),
                new CheckoutReserveService.RequestItem("b", 2)));
    assertThat(CheckoutReserveService.requestHash(one))
        .isNotEqualTo(CheckoutReserveService.requestHash(two));
  }

  @Test
  void sameRequestHashesEqual() {
    CheckoutReserveService.ReserveRequest request =
        new CheckoutReserveService.ReserveRequest(
            "chk", "shop", List.of(new CheckoutReserveService.RequestItem("L-1", 2)));
    assertThat(CheckoutReserveService.requestHash(request))
        .isEqualTo(CheckoutReserveService.requestHash(request));
  }
}
