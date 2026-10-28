package com.thaishopfun.mocktsf.events;

import static org.assertj.core.api.Assertions.*;

import com.thaishopfun.mocktsf.ApiException;
import com.thaishopfun.mocktsf.Hmacs;
import com.thaishopfun.mocktsf.MockProperties;
import com.thaishopfun.mocktsf.contract.ContractResponses;
import com.thaishopfun.mocktsf.rest.TsfCatalog;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.json.JsonMapper;

class StockPushApplicationTest {
  private final JsonMapper json = new JsonMapper();
  private final TsfCatalog catalog = new TsfCatalog();
  private final MockProperties properties = new MockProperties();
  private final ReceivedEventStore store = new ReceivedEventStore();
  private final OmsEventsController controller =
      new OmsEventsController(properties, new ContractResponses(json), store, json, catalog);

  @Test
  void repairUpdatesVisibleQuantityAndVersionAndIgnoresDuplicatesAndStaleItems() throws Exception {
    catalog.forceListingQuantity("tsf_sku_7781", 3);
    assertThat(controller.receive(request("repair", 1043, 18)).getStatusCode().value())
        .isEqualTo(202);
    assertThat(listing()).containsEntry("available", 18).containsEntry("stock_version", 1043L);
    catalog.forceListingQuantity("tsf_sku_7781", 2);
    assertThat(controller.receive(request("repair", 1043, 18)).getStatusCode().value())
        .isEqualTo(200);
    controller.receive(request("equal", 1043, 17));
    controller.receive(request("older", 1042, 16));
    assertThat(listing()).containsEntry("available", 2).containsEntry("stock_version", 1043L);
    controller.receive(request("repush", 2147483650L, 18));
    assertThat(listing())
        .containsEntry("available", 18)
        .containsEntry("stock_version", 2147483650L);
    assertThat(store.all()).hasSize(4);
  }

  @Test
  void ordersBatchItemsIndependentlyAndUpdatesHiddenListingsWithoutCreatingUnknownIds()
      throws Exception {
    catalog.setListingHidden("tsf_sku_7781", true);
    var body = json.readTree(request("batch", 1044, 10).getContentAsByteArray());
    var items = (tools.jackson.databind.node.ArrayNode) body.path("data").path("items");
    items.add(
        json.valueToTree(
            Map.of(
                "listing_sku_id",
                "tsf_sku_9001",
                "seller_sku",
                "MUG-WHT",
                "available",
                0,
                "stock_version",
                881)));
    items.add(
        json.valueToTree(
            Map.of(
                "listing_sku_id",
                "unknown",
                "seller_sku",
                "UNKNOWN",
                "available",
                5,
                "stock_version",
                9999)));
    controller.receive(signed("batch", json.writeValueAsBytes(body)));
    catalog.setListingHidden("tsf_sku_7781", false);
    assertThat(listing()).containsEntry("available", 10);
    assertThat(
            catalog.listings().stream()
                .filter(row -> row.get("listing_sku_id").equals("tsf_sku_9001"))
                .findFirst()
                .orElseThrow())
        .containsEntry("available", 0);
    assertThat(catalog.listings()).noneMatch(row -> row.get("listing_sku_id").equals("unknown"));
  }

  @Test
  void invalidSignatureSchemaOrEventHeaderCannotMutateStock() throws Exception {
    var badSignature = request("bad-signature", 1043, 0);
    badSignature.removeHeader("X-Signature");
    assertThatThrownBy(() -> controller.receive(badSignature)).isInstanceOf(ApiException.class);
    var badHeader = request("bad-header", 1043, 0);
    badHeader.removeHeader("X-Event-Id");
    assertThatThrownBy(() -> controller.receive(badHeader)).isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> controller.receive(request("bad-schema", 1043, -1)))
        .isInstanceOf(ApiException.class);
    assertThat(listing()).containsEntry("available", 18).containsEntry("stock_version", 1042);
    assertThat(store.all()).isEmpty();
  }

  private Map<String, Object> listing() {
    return catalog.listings().stream()
        .filter(row -> row.get("listing_sku_id").equals("tsf_sku_7781"))
        .findFirst()
        .orElseThrow();
  }

  private MockHttpServletRequest request(String id, long version, int qty) {
    String body =
        """
        {"event_id":"%s","event_type":"stock.updated","schema_version":1,
         "occurred_at":"2026-10-28T08:00:00Z","tsf_shop_id":"shop_active",
         "aggregate_id":"stock","aggregate_version":1,"data":{"items":[
         {"listing_sku_id":"tsf_sku_7781","seller_sku":"TSHIRT-BLK-M","available":%d,"stock_version":%d}]}}
        """
            .formatted(id, qty, version);
    return signed(id, body.getBytes(StandardCharsets.UTF_8));
  }

  private MockHttpServletRequest signed(String id, byte[] body) {
    var request = new MockHttpServletRequest();
    request.setContent(body);
    request.addHeader("X-Event-Id", id);
    request.addHeader(
        "X-Signature",
        Hmacs.header(properties.getOutboxHmacSecret(), Instant.now().getEpochSecond(), body));
    return request;
  }
}
