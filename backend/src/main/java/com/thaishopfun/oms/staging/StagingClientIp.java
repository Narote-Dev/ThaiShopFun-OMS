package com.thaishopfun.oms.staging;

import jakarta.servlet.http.HttpServletRequest;

final class StagingClientIp {

  private StagingClientIp() {}

  static String resolve(HttpServletRequest request) {
    String forwarded = request.getHeader("X-Forwarded-For");
    if (forwarded != null && !forwarded.isBlank()) {
      int comma = forwarded.indexOf(',');
      return (comma < 0 ? forwarded : forwarded.substring(0, comma)).trim();
    }
    String realIp = request.getRemoteAddr();
    return realIp == null ? "unknown" : realIp;
  }
}
