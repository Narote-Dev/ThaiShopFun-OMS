package com.thaishopfun.oms.staging;

/** Throttle bucket keys. Lockout follows {@linkplain #forLogin login id} only, not client IP. */
final class StagingLoginThrottleKeys {

  private StagingLoginThrottleKeys() {}

  static String forLogin(String loginId) {
    if (loginId == null || loginId.isBlank()) {
      return "login:-";
    }
    return "login:" + loginId.trim();
  }

  /**
   * Separate sliding window per client address (optional; does not share lockout with login id).
   */
  static String forIp(String clientIp) {
    String ip = clientIp == null || clientIp.isBlank() ? "unknown" : clientIp.trim();
    return "ip:" + ip;
  }
}
