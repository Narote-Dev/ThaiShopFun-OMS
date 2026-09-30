package com.thaishopfun.oms.warehouse;

import tools.jackson.databind.JsonNode;

/** Create or full update. {@code address} is a JSON object or null. */
public record WarehouseRequest(String code, String name, JsonNode address) {}
