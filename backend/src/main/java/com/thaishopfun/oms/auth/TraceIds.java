package com.thaishopfun.oms.auth;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;

final class TraceIds {

  static final String ATTRIBUTE = "oms.trace_id";

  private TraceIds() {}

  static String current(HttpServletRequest request) {
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
