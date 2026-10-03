package com.thaishopfun.oms.order.demo;

import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile({"local", "e2e"})
@RequestMapping("/control/demo")
public class OrderDemoCatalogController {

  private final OrderDemoCatalogService catalog;

  public OrderDemoCatalogController(OrderDemoCatalogService catalog) {
    this.catalog = catalog;
  }

  @PostMapping("/order-catalog")
  public ResponseEntity<Map<String, Object>> seedCatalog() {
    return ResponseEntity.ok(catalog.ensureDemoCatalog());
  }
}
