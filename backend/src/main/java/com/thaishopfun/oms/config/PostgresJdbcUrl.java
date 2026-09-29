package com.thaishopfun.oms.config;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/**
 * Turns a Railway-style {@code postgres://} or {@code postgresql://} URL into a JDBC URL. JDBC URLs
 * are left untouched. The raw URL is never included in exception text because it can carry a
 * password.
 */
final class PostgresJdbcUrl {

  private PostgresJdbcUrl() {}

  record Normalized(String jdbcUrl, String username, String password) {}

  static Normalized normalize(String url) {
    // Step 1: Validate — only rewrite non-JDBC postgres URLs.
    if (url == null || url.isBlank() || url.startsWith("jdbc:")) {
      return null;
    }
    String raw = url;
    if (raw.startsWith("postgres://")) {
      raw = "postgresql://" + raw.substring("postgres://".length());
    }
    if (!raw.startsWith("postgresql://")) {
      return null;
    }

    // Step 2: Parse host, credentials, and query without logging the secret.
    URI uri;
    try {
      uri = URI.create(raw);
    } catch (IllegalArgumentException ex) {
      throw new IllegalArgumentException("DATABASE_URL is not a valid postgres URL", ex);
    }
    if (uri.getHost() == null) {
      throw new IllegalArgumentException("DATABASE_URL is not a valid postgres URL");
    }

    String username = null;
    String password = null;
    String userInfo = uri.getRawUserInfo();
    if (userInfo != null && !userInfo.isEmpty()) {
      int colon = userInfo.indexOf(':');
      if (colon >= 0) {
        username = decode(userInfo.substring(0, colon));
        password = decode(userInfo.substring(colon + 1));
      } else {
        username = decode(userInfo);
      }
    }

    // Step 3: Build the JDBC URL Spring's driver expects.
    StringBuilder jdbc = new StringBuilder("jdbc:postgresql://");
    jdbc.append(uri.getHost());
    if (uri.getPort() != -1) {
      jdbc.append(':').append(uri.getPort());
    }
    if (uri.getPath() != null) {
      jdbc.append(uri.getPath());
    }
    if (uri.getRawQuery() != null) {
      jdbc.append('?').append(uri.getRawQuery());
    }
    return new Normalized(jdbc.toString(), username, password);
  }

  private static String decode(String value) {
    return URLDecoder.decode(value, StandardCharsets.UTF_8);
  }
}
