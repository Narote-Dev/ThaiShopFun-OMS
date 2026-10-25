package com.thaishopfun.oms.order.demo;

import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile({"local", "e2e"})
@RequestMapping("/control/demo")
class DemoStockControlController {
  private final OrderDemoCatalogService catalog;

  DemoStockControlController(OrderDemoCatalogService catalog) {
    this.catalog = catalog;
  }

  @PostMapping("/stock-control-mode")
  Map<String, Object> mode(
      @RequestBody Map<String, Object> body, jakarta.servlet.http.HttpServletRequest request) {
    if (!java.util.Set.of("127.0.0.1", "::1", "0:0:0:0:0:0:0:1").contains(request.getRemoteAddr()))
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.FORBIDDEN);
    Object mode = body.get("mode");
    if (!"CONTROL".equals(mode) && !"ACTIVE".equals(mode))
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY);
    return catalog.stockControlMode(
        "CONTROL".equals(mode), Boolean.TRUE.equals(body.get("reset_allowlist")));
  }
}
