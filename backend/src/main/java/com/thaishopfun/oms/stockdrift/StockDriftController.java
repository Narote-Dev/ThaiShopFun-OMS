package com.thaishopfun.oms.stockdrift;

import com.thaishopfun.oms.listing.ChannelListingAccess;
import com.thaishopfun.oms.listing.ListingApiException;
import com.thaishopfun.oms.tenant.TenantContext;
import java.util.Map;
import java.util.Set;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/stock-drift")
public class StockDriftController {
  private final StockDriftService service;
  private final ChannelListingAccess access;

  public StockDriftController(StockDriftService service, ChannelListingAccess access) {
    this.service = service;
    this.access = access;
  }

  @GetMapping
  public Map<String, Object> issues(@RequestParam(required = false) String status) {
    if (status != null && !Set.of("OPEN", "ACK", "RESOLVED").contains(status))
      throw new ListingApiException(422, "VALIDATION_FAILED", "status is invalid");
    return Map.of("items", service.issues(status));
  }

  @PostMapping("/runs")
  public StockDriftService.Result run() {
    access.requireWriter();
    return service.run(TenantContext.requireTenantId());
  }
}
