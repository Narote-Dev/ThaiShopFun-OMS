package com.thaishopfun.mocktsf.rest;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.thaishopfun.mocktsf.ApiException;
import com.thaishopfun.mocktsf.contract.ContractResponses;
import com.thaishopfun.mocktsf.events.ControlController;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.json.JsonMapper;

class StockDriftControlTest {
  private final JsonMapper json = new JsonMapper();
  private final TsfCatalog catalog = new TsfCatalog();
  private final ControlController controller =
      new ControlController(
          json, new ContractResponses(json), null, null, null, null, null, null, null, catalog);

  @Test
  void forceQuantityIsContractValidAndVisibleToTheListingGetWithoutChangingVersion()
      throws Exception {
    var before =
        catalog.listings().stream()
            .filter(row -> row.get("listing_sku_id").equals("tsf_sku_7781"))
            .findFirst()
            .orElseThrow();
    var request = request("{\"available\":3}");
    var response = controller.listingQuantity("tsf_sku_7781", request);
    assertThat(response.getStatusCode().value()).isEqualTo(200);
    var after =
        catalog.listings().stream()
            .filter(row -> row.get("listing_sku_id").equals("tsf_sku_7781"))
            .findFirst()
            .orElseThrow();
    assertThat(after)
        .containsEntry("available", 3)
        .containsEntry("stock_version", before.get("stock_version"));
    assertThat(before).containsEntry("available", 18); // snapshots cannot change during pagination
    assertThat(json.readTree(response.getBody()).path("available").asInt()).isEqualTo(3);
  }

  @Test
  void rejectsInvalidQuantityAndMissingListing() throws Exception {
    for (String body :
        new String[] {
          "{\"available\":-1}", "{\"available\":1.5}", "{\"available\":2147483648}", "{}"
        })
      assertThatThrownBy(() -> controller.listingQuantity("tsf_sku_7781", request(body)))
          .isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> catalog.forceListingQuantity("missing", 1))
        .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.status()).isEqualTo(404));
  }

  private MockHttpServletRequest request(String body) {
    var request = new MockHttpServletRequest();
    request.setContent(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    return request;
  }
}
