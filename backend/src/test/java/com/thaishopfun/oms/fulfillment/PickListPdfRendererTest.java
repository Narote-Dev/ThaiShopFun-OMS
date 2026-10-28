package com.thaishopfun.oms.fulfillment;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PickListPdfRendererTest {

  @Test
  void rendersA6PdfWithThaiHeader() {
    var renderer = new PickListPdfRenderer();
    UUID id = UUID.fromString("00000000-0000-4000-8000-000000000001");
    var detail =
        new FulfillmentViews.PickListDetail(
            id,
            UUID.randomUUID(),
            "MAIN",
            Instant.parse("2026-10-28T10:00:00Z"),
            List.of(new FulfillmentViews.PickListOrderRef(id, "ORD-1", 0)),
            List.of(
                new FulfillmentViews.PickLine(
                    UUID.randomUUID(), "TEE-1", "8850001", "เสื้อทดสอบ", "MAIN", 3)));
    byte[] pdf = renderer.renderA6(detail);
    assertThat(new String(pdf, 0, 4, StandardCharsets.US_ASCII)).isEqualTo("%PDF");
    assertThat(pdf.length).isGreaterThan(500);
  }
}
