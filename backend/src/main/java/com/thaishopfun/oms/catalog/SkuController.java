package com.thaishopfun.oms.catalog;

import java.net.URI;
import java.util.List;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/skus")
class SkuController {

  private final SkuService skus;

  SkuController(SkuService skus) {
    this.skus = skus;
  }

  @GetMapping
  ResponseEntity<PageResult<SkuView>> list(
      @RequestParam(name = "q", required = false) String q,
      @RequestParam(name = "product_id", required = false) UUID productId,
      @RequestParam(name = "limit", required = false) Integer limit,
      @RequestParam(name = "offset", required = false) Integer offset) {
    return ProductController.ok(skus.list(q, productId, limit, offset));
  }

  @PostMapping
  ResponseEntity<SkuView> create(@RequestBody SkuRequest request) {
    SkuView created = skus.create(request);
    return ResponseEntity.created(URI.create("/api/v1/skus/" + created.id()))
        .cacheControl(CacheControl.noStore())
        .body(created);
  }

  @GetMapping("/{id}")
  ResponseEntity<SkuView> get(@PathVariable UUID id) {
    return ProductController.ok(skus.get(id));
  }

  @PutMapping("/{id}")
  ResponseEntity<SkuView> update(@PathVariable UUID id, @RequestBody SkuRequest request) {
    return ProductController.ok(skus.update(id, request));
  }

  @PutMapping("/{id}/components")
  ResponseEntity<SkuView> replaceComponents(
      @PathVariable UUID id, @RequestBody List<ComponentRequest> components) {
    return ProductController.ok(skus.replaceComponents(id, components));
  }

  @DeleteMapping("/{id}")
  ResponseEntity<Void> delete(@PathVariable UUID id) {
    skus.delete(id);
    return ResponseEntity.noContent().build();
  }
}
