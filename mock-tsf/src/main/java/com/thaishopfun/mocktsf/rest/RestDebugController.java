package com.thaishopfun.mocktsf.rest;

import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Profile("!prod")
@RestController
@RequestMapping("/control/rest")
public class RestDebugController {

  private final TsfCatalog catalog;

  public RestDebugController(TsfCatalog catalog) {
    this.catalog = catalog;
  }

  @GetMapping("/cancel-hits/{orderId}")
  public Map<String, Object> cancelHits(@PathVariable String orderId) {
    List<TsfCatalog.CancelHit> hits = catalog.cancelHits(orderId);
    return Map.of("order_id", orderId, "hits", hits);
  }

  @DeleteMapping("/cancel-hits/{orderId}")
  public Map<String, Object> clearCancelHits(@PathVariable String orderId) {
    catalog.clearCancelHits(orderId);
    return Map.of("order_id", orderId, "cleared", true);
  }

  @PostMapping("/demo-order/{orderId}")
  public Map<String, Object> ensureDemoOrder(@PathVariable String orderId) {
    catalog.ensureDemoOrder(orderId);
    return Map.of("order_id", orderId, "registered", true);
  }
}
