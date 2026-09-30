package com.thaishopfun.oms.catalog;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public record ProductView(
    UUID id,
    String name,
    String status,
    @JsonProperty("sku_count") long skuCount,
    @JsonProperty("created_at") Instant createdAt,
    @JsonProperty("updated_at") Instant updatedAt) {

  Map<String, Object> audit() {
    Map<String, Object> values = new LinkedHashMap<>();
    values.put("name", name);
    values.put("status", status);
    return values;
  }
}
