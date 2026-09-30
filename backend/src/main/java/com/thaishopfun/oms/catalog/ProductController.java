package com.thaishopfun.oms.catalog;

import java.net.URI;
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
@RequestMapping("/api/v1/products")
class ProductController {

  private final ProductService products;

  ProductController(ProductService products) {
    this.products = products;
  }

  @GetMapping
  ResponseEntity<PageResult<ProductView>> list(
      @RequestParam(name = "q", required = false) String q,
      @RequestParam(name = "status", required = false) String status,
      @RequestParam(name = "limit", required = false) Integer limit,
      @RequestParam(name = "offset", required = false) Integer offset) {
    return ok(products.list(q, status, limit, offset));
  }

  @PostMapping
  ResponseEntity<ProductView> create(@RequestBody ProductRequest request) {
    ProductView created = products.create(request);
    return ResponseEntity.created(URI.create("/api/v1/products/" + created.id()))
        .cacheControl(CacheControl.noStore())
        .body(created);
  }

  @GetMapping("/{id}")
  ResponseEntity<ProductView> get(@PathVariable UUID id) {
    return ok(products.get(id));
  }

  @PutMapping("/{id}")
  ResponseEntity<ProductView> update(@PathVariable UUID id, @RequestBody ProductRequest request) {
    return ok(products.update(id, request));
  }

  @PostMapping("/{id}/archive")
  ResponseEntity<ProductView> archive(@PathVariable UUID id) {
    return ok(products.archive(id));
  }

  @DeleteMapping("/{id}")
  ResponseEntity<Void> delete(@PathVariable UUID id) {
    products.delete(id);
    return ResponseEntity.noContent().build();
  }

  static <T> ResponseEntity<T> ok(T body) {
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
  }
}
