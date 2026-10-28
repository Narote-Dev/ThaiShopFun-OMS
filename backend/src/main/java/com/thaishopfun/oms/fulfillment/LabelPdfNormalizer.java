package com.thaishopfun.oms.fulfillment;

import com.thaishopfun.oms.order.web.OrderApiException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.stereotype.Component;

/** Validates carrier label PDFs and normalizes the first page to A6 thermal size. */
@Component
class LabelPdfNormalizer {

  private static final float MM = 72f / 25.4f;
  static final PDRectangle A6 = new PDRectangle(105 * MM, 148 * MM);

  byte[] validateAndNormalizeA6(byte[] pdf) {
    if (pdf == null || pdf.length == 0) {
      throw invalid("Label PDF is empty");
    }
    try (PDDocument source = Loader.loadPDF(pdf)) {
      if (source.getNumberOfPages() < 1) {
        throw invalid("Label PDF has no pages");
      }
      PDFRenderer renderer = new PDFRenderer(source);
      var image = renderer.renderImageWithDPI(0, 203);
      try (PDDocument target = new PDDocument()) {
        PDPage page = new PDPage(A6);
        target.addPage(page);
        PDImageXObject xObject = PDImageXObject.createFromByteArray(target, toPng(image), "label");
        float scale =
            Math.min(A6.getWidth() / xObject.getWidth(), A6.getHeight() / xObject.getHeight());
        float width = xObject.getWidth() * scale;
        float height = xObject.getHeight() * scale;
        float x = (A6.getWidth() - width) / 2f;
        float y = (A6.getHeight() - height) / 2f;
        try (PDPageContentStream stream = new PDPageContentStream(target, page)) {
          stream.drawImage(xObject, x, y, width, height);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        target.save(out);
        return out.toByteArray();
      }
    } catch (IOException ex) {
      throw invalid("Label PDF is not readable");
    }
  }

  private static byte[] toPng(java.awt.image.BufferedImage image) throws IOException {
    ByteArrayOutputStream png = new ByteArrayOutputStream();
    javax.imageio.ImageIO.write(image, "png", png);
    return png.toByteArray();
  }

  private static OrderApiException invalid(String message) {
    return new OrderApiException(422, "LABEL_PDF_INVALID", message);
  }
}
