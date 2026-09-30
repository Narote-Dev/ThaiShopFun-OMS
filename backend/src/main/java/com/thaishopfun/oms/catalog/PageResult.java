package com.thaishopfun.oms.catalog;

import java.util.List;

/** Offset page. {@code total} counts every match, not just this page. */
public record PageResult<T>(List<T> items, long total, int limit, int offset) {

  public static final int DEFAULT_LIMIT = 50;
  public static final int MAX_LIMIT = 200;

  public static int limit(Integer requested) {
    if (requested == null) {
      return DEFAULT_LIMIT;
    }
    return Math.min(Math.max(requested, 1), MAX_LIMIT);
  }

  public static int offset(Integer requested) {
    return requested == null ? 0 : Math.max(requested, 0);
  }
}
