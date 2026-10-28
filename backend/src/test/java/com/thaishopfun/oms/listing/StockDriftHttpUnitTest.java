package com.thaishopfun.oms.listing;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.thaishopfun.oms.stockdrift.StockDriftController;
import com.thaishopfun.oms.stockdrift.StockDriftService;
import com.thaishopfun.oms.tenant.TenantContext;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class StockDriftHttpUnitTest {
  private final StockDriftService service = mock(StockDriftService.class);
  private final ChannelListingAccess access = mock(ChannelListingAccess.class);
  private final org.springframework.test.web.servlet.MockMvc http =
      MockMvcBuilders.standaloneSetup(new StockDriftController(service, access))
          .setControllerAdvice(new ListingErrorHandler())
          .build();

  @AfterEach
  void cleanup() {
    TenantContext.clear();
  }

  @Test
  void readerCannotRunAndErrorsUseTheOmsContract() throws Exception {
    doThrow(ListingApiException.forbidden()).when(access).requireWriter();
    http.perform(post("/api/v1/stock-drift/runs"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error").value("FORBIDDEN"))
        .andExpect(jsonPath("$.trace_id").isNotEmpty());
    http.perform(get("/api/v1/stock-drift").param("status", "UNKNOWN"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"));
    verifyNoInteractions(service);
  }

  @Test
  void statusFilterAndAdminRunUseTheCurrentTenant() throws Exception {
    when(service.issues("ACK")).thenReturn(List.of());
    http.perform(get("/api/v1/stock-drift").param("status", "ACK"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items").isArray());
    UUID tenant = UUID.randomUUID();
    UUID run = UUID.randomUUID();
    TenantContext.set(tenant, UUID.randomUUID());
    when(service.run(tenant)).thenReturn(new StockDriftService.Result(run, 2, 1, false));
    http.perform(post("/api/v1/stock-drift/runs"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.drifting").value(2))
        .andExpect(jsonPath("$.run_id").value(run.toString()));
    verify(access).requireWriter();
    verify(service).run(tenant);
  }
}
