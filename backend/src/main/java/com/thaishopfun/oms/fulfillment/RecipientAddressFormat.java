package com.thaishopfun.oms.fulfillment;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Formats stored recipient address JSON for packing slips (mirrors frontend formatPackAddress). */
final class RecipientAddressFormat {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private RecipientAddressFormat() {}

  static String formatSingleBlock(String address, String province, String postcode) {
    String prov = trimOrEmpty(province);
    String pc = trimOrEmpty(postcode);
    String raw = address == null ? "" : address.trim();
    StringBuilder out = new StringBuilder();
    if (raw.startsWith("{")) {
      try {
        JsonNode obj = JSON.readTree(raw);
        appendLine(out, obj, "line1");
        appendLine(out, obj, "line2");
        appendLine(out, obj, "subdistrict");
        appendLine(out, obj, "district");
        if (obj.hasNonNull("province")) {
          prov = obj.get("province").asString().trim();
        }
        if (obj.hasNonNull("postcode")) {
          pc = obj.get("postcode").asString().trim();
        }
      } catch (Exception ex) {
        if (!raw.isEmpty()) {
          appendSegment(out, raw);
        }
      }
    } else if (!raw.isEmpty()) {
      appendSegment(out, raw);
    }
    String locality =
        String.join(" ", java.util.stream.Stream.of(prov, pc).filter(s -> !s.isBlank()).toList());
    if (!locality.isBlank()) {
      appendSegment(out, locality);
    }
    return out.isEmpty() ? "—" : out.toString();
  }

  private static void appendLine(StringBuilder out, JsonNode obj, String field) {
    if (obj.hasNonNull(field)) {
      String value = obj.get(field).asString().trim();
      if (!value.isEmpty()) {
        appendSegment(out, value);
      }
    }
  }

  private static void appendSegment(StringBuilder out, String segment) {
    if (!out.isEmpty()) {
      out.append(" · ");
    }
    out.append(segment);
  }

  private static String trimOrEmpty(String value) {
    return value == null ? "" : value.trim();
  }
}
