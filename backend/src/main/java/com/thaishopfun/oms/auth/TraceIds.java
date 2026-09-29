package com.thaishopfun.oms.auth;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;

// Change: public so the outbox admin API can return the same trace_id as other errors.
public final class TraceIds {

  public static final String ATTRIBUTE = "oms.trace_id";

  private TraceIds() {}

  public static String current(HttpServletRequest request) {
    Object existing = request.getAttribute(ATTRIBUTE);
    if (existing instanceof String text && !text.isEmpty()) {
      return text;
    }
    String created = newId();
    request.setAttribute(ATTRIBUTE, created);
    return created;
  }

  static String newId() {
    return UUID.randomUUID().toString().replace("-", "");
  }
}
