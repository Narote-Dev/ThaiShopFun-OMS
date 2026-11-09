package com.thaishopfun.oms.listing;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.thaishopfun.oms.reconciliation.ReconciliationController;
import com.thaishopfun.oms.reconciliation.ReconciliationService;
import com.thaishopfun.oms.tenant.TenantContext;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ReconciliationHttpUnitTest {
  private final ReconciliationService service = mock(ReconciliationService.class);
  private final ChannelListingAccess access = mock(ChannelListingAccess.class);
  private final org.springframework.test.web.servlet.MockMvc http =
      MockMvcBuilders.standaloneSetup(new ReconciliationController(service, access))
          .setControllerAdvice(new ListingErrorHandler())
          .build();

  @AfterEach
  void cleanup() {
    TenantContext.clear();
  }

  @Test
  void readerCannotRunAndErrorsUseTheOmsContract() throws Exception {
    doThrow(ListingApiException.forbidden()).when(access).requireWriter();
    http.perform(post("/api/v1/reconciliation/runs"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error").value("FORBIDDEN"))
        .andExpect(jsonPath("$.trace_id").isNotEmpty());
    http.perform(get("/api/v1/reconciliation/issues").param("status", "UNKNOWN"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"));
    verifyNoInteractions(service);
  }

  @Test
  void adminRunPatchAndListUseTenantContext() throws Exception {
    when(service.listIssues(null, null)).thenReturn(List.of());
    http.perform(get("/api/v1/reconciliation/issues"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items").isArray());
    UUID tenant = UUID.randomUUID();
    UUID run = UUID.randomUUID();
    UUID issue = UUID.randomUUID();
    TenantContext.set(tenant, UUID.randomUUID());
    when(service.run(tenant, "MANUAL"))
        .thenReturn(new ReconciliationService.Result(run, 3, Map.of("SHIP_BY_LATE", 1)));
    http.perform(post("/api/v1/reconciliation/runs"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.run_id").value(run.toString()));
    doNothing().when(service).patchStatus(issue, "ACK");
    http.perform(
            patch("/api/v1/reconciliation/issues/" + issue)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"ACK\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("ACK"));
    verify(access, times(2)).requireWriter();
  }
}
