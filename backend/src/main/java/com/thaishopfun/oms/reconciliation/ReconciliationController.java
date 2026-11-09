package com.thaishopfun.oms.reconciliation;

import com.thaishopfun.oms.listing.ChannelListingAccess;
import com.thaishopfun.oms.listing.ListingApiException;
import com.thaishopfun.oms.tenant.TenantContext;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/reconciliation")
public class ReconciliationController {

  private final ReconciliationService service;
  private final ChannelListingAccess access;

  public ReconciliationController(ReconciliationService service, ChannelListingAccess access) {
    this.service = service;
    this.access = access;
  }

  @GetMapping("/issues")
  public Map<String, Object> issues(
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String rule) {
    if (status != null && !Set.of("OPEN", "ACK", "RESOLVED").contains(status)) {
      throw new ListingApiException(422, "VALIDATION_FAILED", "status is invalid");
    }
    if (rule != null && !ReconciliationRules.ALL.contains(rule)) {
      throw new ListingApiException(422, "VALIDATION_FAILED", "rule is invalid");
    }
    return Map.of("items", service.listIssues(status, rule));
  }

  @PostMapping("/runs")
  public ReconciliationService.Result run() {
    access.requireWriter();
    return service.run(TenantContext.requireTenantId(), "MANUAL");
  }

  public record StatusBody(String status) {}

  @PatchMapping("/issues/{id}")
  public Map<String, Object> patch(@PathVariable UUID id, @RequestBody StatusBody body) {
    access.requireWriter();
    if (body == null || body.status() == null || body.status().isBlank()) {
      throw new ListingApiException(422, "VALIDATION_FAILED", "status is required");
    }
    service.patchStatus(id, body.status());
    return Map.of("id", id.toString(), "status", body.status());
  }
}
