package com.thaishopfun.oms.staging;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Client key for login throttling. Uses {@code getRemoteAddr()} only so clients cannot reset
 * lockouts by spoofing {@code X-Forwarded-For}. Railway/Vercel sit behind proxies; production sets
 * {@code server.forward-headers-strategy=framework} so the container resolves the trusted client
 * address into {@code remoteAddr} before this runs.
 */
final class StagingClientIp {

  private StagingClientIp() {}

  static String resolve(HttpServletRequest request) {
    String realIp = request.getRemoteAddr();
    return realIp == null || realIp.isBlank() ? "unknown" : realIp.trim();
  }

  static String throttleKey(String clientIp, String loginId) {
    String user = loginId == null || loginId.isBlank() ? "-" : loginId.trim();
    return clientIp + "|" + user;
  }
}
