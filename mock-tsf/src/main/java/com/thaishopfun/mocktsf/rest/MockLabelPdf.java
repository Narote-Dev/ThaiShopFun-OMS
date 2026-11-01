package com.thaishopfun.mocktsf.rest;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

/** One-page A6 carrier label stub for mock TSF GET /shipments/{id}/label. */
final class MockLabelPdf {

  private static final float MM = 72f / 25.4f;
  private static final PDRectangle A6 = new PDRectangle(105 * MM, 148 * MM);

  private MockLabelPdf() {}

  static byte[] a6Label(String shipmentId) {
    try (PDDocument document = new PDDocument()) {
      PDPage page = new PDPage(A6);
      document.addPage(page);
      PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
      try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
        stream.beginText();
        stream.setFont(font, 12);
        stream.newLineAtOffset(10 * MM, A6.getHeight() - 15 * MM);
        stream.showText("Mock TSF Label");
        stream.newLineAtOffset(0, -8 * MM);
        stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 9);
        stream.showText(shipmentId == null ? "shipment" : shipmentId);
        stream.endText();
      }
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      document.save(out);
      return out.toByteArray();
    } catch (IOException ex) {
      throw new IllegalStateException("Failed to render mock label PDF", ex);
    }
  }
}
