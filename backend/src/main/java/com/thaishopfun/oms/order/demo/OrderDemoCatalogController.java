package com.thaishopfun.oms.order.demo;

import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile({"local", "e2e", "test"})
@RequestMapping("/control/demo")
public class OrderDemoCatalogController {

  private final OrderDemoCatalogService catalog;

  public OrderDemoCatalogController(OrderDemoCatalogService catalog) {
    this.catalog = catalog;
  }

  @PostMapping("/order-catalog")
  public ResponseEntity<Map<String, Object>> seedCatalog() {
    try {
      return ResponseEntity.ok(catalog.ensureDemoCatalog());
    } catch (DemoCatalogTenantMissingException ex) {
      return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
          .body(Map.of("status", "UNAVAILABLE", "reason", ex.getMessage()));
    }
  }
}
