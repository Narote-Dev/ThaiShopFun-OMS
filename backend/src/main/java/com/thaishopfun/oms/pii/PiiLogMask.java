package com.thaishopfun.oms.pii;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Last-line log protection. Application code must still avoid logging personal data. */
public final class PiiLogMask {
  private static final Pattern PHONE =
      Pattern.compile("(?<![\\d])(?:0(?:[ -]?\\d){8,9}|\\+?66(?:[ -]?\\d){8,9})(?![\\d])");
  private static final Pattern EMAIL =
      Pattern.compile("[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
  private static final String KEY_CHARS = "[\\p{L}\\p{M}\\p{N}_.-]";
  private static final String[] KEY_FRAGMENTS = {
    "name", "recipient", "address", "phone", "email", "tel", "mobile", "ชื่อ", "ที่อยู่", "เบอร์"
  };
  private static final String[] EXACT_KEYS = {
    "line1", "line2", "district", "subdistrict", "street", "note"
  };
  private static final String PII_KEY =
      KEY_CHARS
          + "*(?:"
          + String.join("|", KEY_FRAGMENTS)
          + ")"
          + KEY_CHARS
          + "*|"
          + String.join("|", EXACT_KEYS);
  private static final String QUOTED_VALUE = "\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'";
  private static final Pattern JSON =
      Pattern.compile(
          "(?i)(\"(?:" + PII_KEY + ")\"\\s*:\\s*)(\"(?:\\\\.|[^\"\\\\])*\"|[^\\s,{}\\[\\]\"]+)");
  private static final Pattern STRUCTURED =
      Pattern.compile("(?i)(\"(?:" + PII_KEY + ")\"\\s*:\\s*)(?=[{\\[])");
  // Unquoted values run to a delimiter, line end, or the next key/value pair.
  private static final Pattern PAIRS =
      Pattern.compile(
          "(?i)(?<![\\p{L}\\p{M}\\p{N}_.\\-\"'])([\"']?(?:"
              + PII_KEY
              + ")[\"']?\\s*[=:]\\s*)("
              + QUOTED_VALUE
              + "|[^\\s,;{}\\[\\]](?:[^,;{}\\[\\]\\r\\n]*?[^\\s,;{}\\[\\]])?)"
              + "(?=\\s+['\"]?"
              + KEY_CHARS
              + "+['\"]?\\s*[=:]|\\s*[,;{}\\[\\]\\r\\n]|\\s*$)");

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
    String masked = line;
    // Most log fields are non-PII (event=..., attempts=...). A delimiter alone must not
    // trigger all the expensive Unicode key/value patterns. These are necessary-condition
    // checks only: the original patterns still decide what is PII and what gets replaced.
    if ((line.indexOf('=') >= 0 || line.indexOf(':') >= 0) && hasKeyCandidate(line)) {
      if (line.indexOf('"') >= 0) {
        masked = JSON.matcher(maskStructured(line)).replaceAll("$1\"[PII]\"");
      }
      masked =
          PAIRS
              .matcher(masked)
              .replaceAll(
                  match -> {
                    char first = match.group(2).charAt(0);
                    String value =
                        first == '\"' || first == '\'' ? first + "[PII]" + first : "[PII]";
                    return Matcher.quoteReplacement(match.group(1) + value);
                  });
    }
    if (masked.indexOf('0') >= 0 || masked.indexOf('6') >= 0) {
      masked = PHONE.matcher(masked).replaceAll("[PII]");
    }
    if (masked.indexOf('@') >= 0) {
      masked = EMAIL.matcher(masked).replaceAll("[PII]");
    }
    return masked;
  }

  private static boolean hasKeyCandidate(String line) {
    String lower = line.toLowerCase(Locale.ROOT);
    for (String fragment : KEY_FRAGMENTS) {
      if (lower.contains(fragment)) return true;
    }
    for (String key : EXACT_KEYS) {
      if (lower.contains(key)) return true;
    }
    return false;
  }
}
