package com.thaishopfun.oms.warehouse;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

public record WarehouseView(
    UUID id,
    String code,
    String name,
    JsonNode address,
    @JsonProperty("is_default") boolean isDefault,
    @JsonProperty("created_at") Instant createdAt,
    @JsonProperty("updated_at") Instant updatedAt) {

  /** Audit values. The address is not copied, only whether one is set. */
  Map<String, Object> audit() {
    Map<String, Object> values = new LinkedHashMap<>();
    values.put("code", code);
    values.put("name", name);
    values.put("is_default", isDefault);
    values.put("address_set", address != null && !address.isNull());
    return values;
  }
}
