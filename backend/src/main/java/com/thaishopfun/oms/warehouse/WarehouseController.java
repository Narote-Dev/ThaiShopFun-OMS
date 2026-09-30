package com.thaishopfun.oms.warehouse;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/warehouses")
class WarehouseController {

  private final WarehouseService warehouses;

  WarehouseController(WarehouseService warehouses) {
    this.warehouses = warehouses;
  }

  @GetMapping
  ResponseEntity<Map<String, List<WarehouseView>>> list() {
    return ok(Map.of("items", warehouses.list()));
  }

  @PostMapping
  ResponseEntity<WarehouseView> create(@RequestBody WarehouseRequest request) {
    WarehouseView created = warehouses.create(request);
    return ResponseEntity.created(URI.create("/api/v1/warehouses/" + created.id()))
        .cacheControl(CacheControl.noStore())
        .body(created);
  }

  @GetMapping("/{id}")
  ResponseEntity<WarehouseView> get(@PathVariable UUID id) {
    return ok(warehouses.get(id));
  }

  @PutMapping("/{id}")
  ResponseEntity<WarehouseView> update(
      @PathVariable UUID id, @RequestBody WarehouseRequest request) {
    return ok(warehouses.update(id, request));
  }

  @PostMapping("/{id}/default")
  ResponseEntity<WarehouseView> setDefault(@PathVariable UUID id) {
    return ok(warehouses.setDefault(id));
  }

  @DeleteMapping("/{id}")
  ResponseEntity<Void> delete(@PathVariable UUID id) {
    warehouses.delete(id);
    return ResponseEntity.noContent().build();
  }

  private static <T> ResponseEntity<T> ok(T body) {
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
  }
}
