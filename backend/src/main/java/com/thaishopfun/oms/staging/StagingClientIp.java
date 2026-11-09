package com.thaishopfun.oms.staging;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Resolves the servlet client address for optional IP rate limiting (not used for login lockout).
 */
final class StagingClientIp {

  private StagingClientIp() {}

  static String resolve(HttpServletRequest request) {
    String realIp = request.getRemoteAddr();
    return realIp == null || realIp.isBlank() ? "unknown" : realIp.trim();
  }
}
