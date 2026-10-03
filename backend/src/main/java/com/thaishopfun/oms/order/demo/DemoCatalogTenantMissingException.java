package com.thaishopfun.oms.order.demo;

/** Raised when {@code resolve_tenant('TSF','shop_active')} returns no row. */
public final class DemoCatalogTenantMissingException extends RuntimeException {

  public DemoCatalogTenantMissingException() {
    super("shop_active tenant not provisioned");
  }
}
