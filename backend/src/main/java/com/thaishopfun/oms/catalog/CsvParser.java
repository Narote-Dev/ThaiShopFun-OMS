package com.thaishopfun.oms.catalog;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * RFC 4180 reader: comma separated, {@code "} quoting with {@code ""} escapes, CRLF or LF. Strict
 * UTF-8 (a leading BOM is dropped). Each record keeps the 1-based file line it starts on, so a
 * quoted field spanning lines does not shift the reported row numbers.
 */
final class CsvParser {

  record Record(int line, List<String> fields) {}

  static final class CsvException extends Exception {

    private final int line;

    CsvException(int line, String message) {
      super(message);
      this.line = line;
    }

    int line() {
      return line;
    }
  }

  private CsvParser() {}

  static List<Record> parse(byte[] content) throws CsvException {
    // Step 1: Decode strictly. A Latin-1 or TIS-620 file must fail, not import mojibake.
    String text;
    try {
      text =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(content))
              .toString();
    } catch (CharacterCodingException ex) {
      throw new CsvException(1, "file is not valid UTF-8");
    }
    if (!text.isEmpty() && text.charAt(0) == '\uFEFF') {
      text = text.substring(1);
    }

    // Step 2: One pass, tracking the line each record starts on.
    List<Record> records = new ArrayList<>();
    List<String> fields = new ArrayList<>();
    StringBuilder field = new StringBuilder();
    int line = 1;
    int recordLine = 1;
    boolean quoted = false;
    boolean fieldStarted = false;
    boolean afterQuote = false;
    int length = text.length();
    for (int i = 0; i < length; i++) {
      char c = text.charAt(i);
      if (quoted) {
        if (c == '"') {
          if (i + 1 < length && text.charAt(i + 1) == '"') {
            field.append('"');
            i++;
          } else {
            quoted = false;
            afterQuote = true;
          }
        } else {
          if (c == '\n') {
            line++;
          }
          field.append(c);
        }
        continue;
      }
      if (c == '"') {
        if (fieldStarted || afterQuote) {
          throw new CsvException(recordLine, "unexpected quote inside a field");
        }
        quoted = true;
        fieldStarted = true;
      } else if (c == ',') {
        fields.add(field.toString());
        field.setLength(0);
        fieldStarted = false;
        afterQuote = false;
      } else if (c == '\r' || c == '\n') {
        if (c == '\r' && i + 1 < length && text.charAt(i + 1) == '\n') {
          i++;
        }
        fields.add(field.toString());
        field.setLength(0);
        addRecord(records, recordLine, fields);
        fields = new ArrayList<>();
        fieldStarted = false;
        afterQuote = false;
        line++;
        recordLine = line;
      } else {
        if (afterQuote) {
          throw new CsvException(recordLine, "unexpected text after a closing quote");
        }
        field.append(c);
        fieldStarted = true;
      }
    }
    if (quoted) {
      throw new CsvException(recordLine, "unterminated quoted field");
    }
    if (fieldStarted || afterQuote || !fields.isEmpty()) {
      fields.add(field.toString());
      addRecord(records, recordLine, fields);
    }
    return records;
  }

  // Step 3: Blank lines are skipped, but still counted for row numbers.
  private static void addRecord(List<Record> records, int line, List<String> fields) {
    if (fields.size() == 1 && fields.get(0).isBlank()) {
      return;
    }
    records.add(new Record(line, List.copyOf(fields)));
  }
}
