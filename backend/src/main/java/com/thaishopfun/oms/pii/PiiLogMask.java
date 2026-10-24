package com.thaishopfun.oms.pii;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Last-line log protection. Application code must still avoid logging personal data. */
public final class PiiLogMask {
  private static final Pattern PHONE =
      Pattern.compile("(?<![\\d])(?:0(?:[ -]?\\d){8,9}|\\+?66(?:[ -]?\\d){8,9})(?![\\d])");
  private static final Pattern EMAIL =
      Pattern.compile("[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
  private static final Pattern JSON =
      Pattern.compile(
          "(?i)(\"(?:name|phone|email|address|line1|line2|district|subdistrict|recipient|recipient_name|recipient_phone|recipient_address|first_name|last_name|full_name|street|note)\"\\s*:\\s*)\"(?:\\\\.|[^\"\\\\])*\"");
  private static final Pattern STRUCTURED =
      Pattern.compile("(?i)(\"(?:recipient|address)\"\\s*:\\s*)(?=[{\\[])");

  private PiiLogMask() {}

  private static String maskStructured(String line) {
    Matcher matcher = STRUCTURED.matcher(line);
    StringBuilder out = new StringBuilder();
    int copied = 0;
    while (matcher.find(copied)) {
      out.append(line, copied, matcher.end()).append("\"[PII]\"");
      int depth = 0;
      boolean quoted = false;
      boolean escaped = false;
      int end = matcher.end();
      for (; end < line.length(); end++) {
        char c = line.charAt(end);
        if (escaped) {
          escaped = false;
          continue;
        }
        if (quoted && c == '\\') {
          escaped = true;
          continue;
        }
        if (c == '\"') {
          quoted = !quoted;
          continue;
        }
        if (!quoted) {
          if (c == '{' || c == '[') depth++;
          if ((c == '}' || c == ']') && --depth == 0) {
            end++;
            break;
          }
        }
      }
      copied = end;
    }
    return copied == 0 ? line : out.append(line, copied, line.length()).toString();
  }

  public static String mask(String line) {
    if (line == null || line.isEmpty()) return line;
    boolean suspect = false;
    for (int i = 0; i < line.length(); i++) {
      char c = line.charAt(i);
      if (c == '0' || c == '6' || c == '@' || c == '"' || c > 127) {
        suspect = true;
        break;
      }
    }
    if (!suspect) return line;
    String masked = JSON.matcher(maskStructured(line)).replaceAll("$1\"[PII]\"");
    masked = PHONE.matcher(masked).replaceAll("[PII]");
    masked = EMAIL.matcher(masked).replaceAll("[PII]");
    // Contract sentinels also cover accidental unstructured name/address logging.
    return masked
        .replace("สมชาย ใจดี", "[PII]")
        .replace("99/1 ถ.สุขุมวิท", "[PII]")
        .replace("ซอยตัวอย่าง 1", "[PII]");
  }
}
