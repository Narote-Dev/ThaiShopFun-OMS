package com.thaishopfun.mocktsf.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.thaishopfun.mocktsf.SeedData;
import com.thaishopfun.mocktsf.rest.TsfCatalog;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class DemoOrdersControllerTest {
  @Test
  void demoSeedRecordsPaidAndCancelledSnapshots() throws Exception {
    OmsCaller oms = mock(OmsCaller.class);
    when(oms.postDemoOrderCatalog()).thenReturn(new OmsCaller.CallResult(200, "{}", true));
    when(oms.postEvent(any(byte[].class), anyString(), any()))
        .thenReturn(new OmsCaller.CallResult(202, "{}", true));
    TsfCatalog catalog = new TsfCatalog();
    DemoOrdersController controller =
        new DemoOrdersController(JsonMapper.builder().build(), oms, new SeedData(), catalog);
    assertThat(controller.seed().getBody().get("sent")).isEqualTo(8);
    var ready = catalog.order("DEMO-READY").orElseThrow();
    assertThat(ready.aggregateVersion()).isEqualTo(6);
    assertThat(ready.payment().get("status")).isEqualTo("PAID");
    assertThat(catalog.order("DEMO-CANCELLED").orElseThrow().detail().get("status"))
        .isEqualTo("CANCELLED");
    controller.seed();
    assertThat(catalog.order("DEMO-READY").orElseThrow().payment().get("status")).isEqualTo("PAID");
  }
}
