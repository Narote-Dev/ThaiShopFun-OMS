package com.thaishopfun.oms.fulfillment;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class LabelBatchPdfRendererTest {

  /** Same minimal valid structure as mock-tsf {@code TsfApiController.LABEL_PDF}. */
  private static final byte[] MINI_PDF =
      ("%PDF-1.1\n"
              + "1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj\n"
              + "2 0 obj<</Type/Pages/Count 0>>endobj\n"
              + "trailer<</Root 1 0 R>>\n"
              + "%%EOF\n")
          .getBytes(StandardCharsets.US_ASCII);

  @Test
  void mergesMultipleLabels() {
    var renderer = new LabelBatchPdfRenderer();
    byte[] merged = renderer.merge(java.util.List.of(MINI_PDF, MINI_PDF));
    assertThat(new String(merged, 0, 4, StandardCharsets.US_ASCII)).isEqualTo("%PDF");
    assertThat(merged.length).isGreaterThan(MINI_PDF.length);
  }
}
