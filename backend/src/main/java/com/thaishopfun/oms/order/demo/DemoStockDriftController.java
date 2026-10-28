package com.thaishopfun.oms.order.demo;

import com.thaishopfun.oms.stockdrift.StockDriftService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@Profile({"local", "e2e"})
@RequestMapping("/control/demo/stock-drift")
class DemoStockDriftController {
  private final JdbcTemplate jdbc;
  private final StockDriftService service;

  DemoStockDriftController(JdbcTemplate jdbc, StockDriftService service) {
    this.jdbc = jdbc;
    this.service = service;
  }

  @PostMapping("/runs")
  StockDriftService.Result run(HttpServletRequest request) {
    if (!Set.of("127.0.0.1", "::1", "0:0:0:0:0:0:0:1").contains(request.getRemoteAddr()))
      throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    UUID tenant = jdbc.queryForObject("SELECT resolve_tenant('TSF', 'shop_active')", UUID.class);
    if (tenant == null) throw new DemoCatalogTenantMissingException();
    return service.run(tenant);
  }
}
