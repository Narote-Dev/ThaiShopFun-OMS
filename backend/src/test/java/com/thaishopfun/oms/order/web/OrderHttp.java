package com.thaishopfun.oms.order.web;

import com.thaishopfun.oms.catalog.CatalogHttp;
import tools.jackson.databind.JsonNode;

/** HTTP helper for orders API tests. */
public final class OrderHttp {

  private final CatalogHttp catalog;

  public OrderHttp(int port) {
    this.catalog = new CatalogHttp(port);
  }

  public CatalogHttp catalog() {
    return catalog;
  }

  public CatalogHttp.Result get(String path, String token) {
    return catalog.get(path, token);
  }

  public CatalogHttp.Result post(String path, String token, Object body) {
    return catalog.post(path, token, body);
  }

  public static String ordersPath(String suffix) {
    return "/api/v1/orders" + suffix;
  }

  public static String id(JsonNode body) {
    return body.path("id").asString();
  }
}
