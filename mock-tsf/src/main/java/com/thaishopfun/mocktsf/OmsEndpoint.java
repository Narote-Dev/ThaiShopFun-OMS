package com.thaishopfun.mocktsf;

import java.net.URI;
import org.springframework.stereotype.Component;

/** Where this mock calls OMS. Tests replace the base URL after OMS has bound a port. */
@Component
public class OmsEndpoint {

  private volatile URI base;

  public OmsEndpoint(MockProperties properties) {
    this.base = URI.create(strip(properties.getOmsBaseUrl()));
  }

  public void setBaseUrl(String url) {
    this.base = URI.create(strip(url));
  }

  public URI base() {
    return base;
  }

  public URI events() {
    return base.resolve("/internal/v1/events");
  }

  public URI reservations() {
    return base.resolve("/internal/v1/inventory/reservations");
  }

  public URI reservation(String reservationId) {
    return base.resolve("/internal/v1/inventory/reservations/" + reservationId);
  }

  private static String strip(String url) {
    if (url == null || url.isBlank()) {
      throw new IllegalStateException("mock.oms-base-url is required");
    }
    String trimmed = url.trim();
    while (trimmed.endsWith("/")) {
      trimmed = trimmed.substring(0, trimmed.length() - 1);
    }
    return trimmed;
  }
}
