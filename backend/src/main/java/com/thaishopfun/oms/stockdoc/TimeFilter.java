package com.thaishopfun.oms.stockdoc;

import com.thaishopfun.oms.catalog.CatalogApiException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;

/**
 * {@code from}/{@code to} query values: an ISO instant ({@code 2026-09-30T03:00:00Z}) or a date
 * ({@code 2026-09-30}) read as a Bangkok calendar day. {@code from} is inclusive; a {@code to} date
 * includes that whole day, a {@code to} instant is exclusive.
 */
final class TimeFilter {

  static final ZoneId SHOP_ZONE = ZoneId.of("Asia/Bangkok");

  private TimeFilter() {}

  static Instant from(String value) {
    return parse("from", value, false);
  }

  static Instant to(String value) {
    return parse("to", value, true);
  }

  private static Instant parse(String field, String value, boolean end) {
    if (value == null || value.isBlank()) {
      return null;
    }
    String text = value.strip();
    try {
      if (text.length() == 10) {
        LocalDate day = LocalDate.parse(text);
        return (end ? day.plusDays(1) : day).atStartOfDay(SHOP_ZONE).toInstant();
      }
      return Instant.parse(text);
    } catch (DateTimeParseException ex) {
      throw CatalogApiException.invalid(field + " must be a date (YYYY-MM-DD) or an ISO instant");
    }
  }
}
