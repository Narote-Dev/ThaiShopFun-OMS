package com.thaishopfun.oms.fulfillment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.thaishopfun.oms.order.web.OrderApiException;
import java.nio.charset.StandardCharsets;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.Test;

class LabelPdfNormalizerTest {

  private final LabelPdfNormalizer normalizer = new LabelPdfNormalizer();

  @Test
  void rejectsZeroPagePdf() {
    byte[] stub =
        ("%PDF-1.1\n"
                + "1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj\n"
                + "2 0 obj<</Type/Pages/Count 0>>endobj\n"
                + "trailer<</Root 1 0 R>>\n"
                + "%%EOF\n")
            .getBytes(StandardCharsets.US_ASCII);
    assertThatThrownBy(() -> normalizer.validateAndNormalizeA6(stub))
        .isInstanceOf(OrderApiException.class)
        .satisfies(
            ex -> assertThat(((OrderApiException) ex).code()).isEqualTo("LABEL_PDF_INVALID"));
  }

  @Test
  void normalizesToA6() throws Exception {
    byte[] pdf;
    try (PDDocument document = new PDDocument()) {
      document.addPage(new PDPage(PDRectangle.A4));
      java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
      document.save(out);
      pdf = out.toByteArray();
    }
    byte[] normalized = normalizer.validateAndNormalizeA6(pdf);
    try (PDDocument read = Loader.loadPDF(normalized)) {
      assertThat(read.getNumberOfPages()).isEqualTo(1);
      PDPage page = read.getPage(0);
      assertThat(page.getMediaBox().getWidth()).isEqualTo(LabelPdfNormalizer.A6.getWidth());
      assertThat(page.getMediaBox().getHeight()).isEqualTo(LabelPdfNormalizer.A6.getHeight());
    }
  }
}
