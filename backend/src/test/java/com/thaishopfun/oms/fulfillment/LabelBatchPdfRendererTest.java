package com.thaishopfun.oms.fulfillment;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.Test;

class LabelBatchPdfRendererTest {

  @Test
  void mergesMultipleLabels() throws Exception {
    byte[] page = onePagePdf();
    var renderer = new LabelBatchPdfRenderer();
    byte[] merged = renderer.merge(java.util.List.of(page, page));
    assertThat(merged.length).isGreaterThan(page.length);
  }

  private static byte[] onePagePdf() throws Exception {
    try (PDDocument document = new PDDocument()) {
      document.addPage(new PDPage(LabelPdfNormalizer.A6));
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      document.save(out);
      return out.toByteArray();
    }
  }
}
