package com.thaishopfun.oms.fulfillment;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/** Renders pick lists for A6 thermal paper (105 × 148 mm). */
@Component
public class PickListPdfRenderer {

  private static final float MM = 72f / 25.4f;
  private static final PDRectangle A6 = new PDRectangle(105 * MM, 148 * MM);
  private static final ZoneId BANGKOK = ZoneId.of("Asia/Bangkok");
  private static final DateTimeFormatter WHEN =
      DateTimeFormatter.ofPattern("d MMM yyyy HH:mm").withZone(BANGKOK);

  public byte[] renderA6(FulfillmentViews.PickListDetail detail) {
    try (PDDocument document = new PDDocument()) {
      PDFont font = loadFont(document);
      PDPage page = new PDPage(A6);
      document.addPage(page);
      float margin = 8 * MM;
      float maxWidth = A6.getWidth() - 2 * margin;
      float y = A6.getHeight() - margin;
      try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
        y = writeFitted(stream, font, 14, margin, y, maxWidth, "ใบหยิบสินค้า");
        y -= 3 * MM;
        String shortId = detail.id().toString().substring(0, 8);
        y =
            writeFitted(
                stream,
                font,
                8,
                margin,
                y,
                maxWidth,
                "รหัส "
                    + shortId
                    + " · คลัง "
                    + detail.warehouseCode()
                    + " · "
                    + WHEN.format(detail.createdAt()));
        y -= 2 * MM;
        y =
            writeFitted(
                stream, font, 8, margin, y, maxWidth, "จำนวนออเดอร์: " + detail.orders().size());
        y -= 4 * MM;
        y = writeFitted(stream, font, 10, margin, y, maxWidth, "รายการหยิบ");
        y -= 2 * MM;
        for (FulfillmentViews.PickLine line : detail.pickLines()) {
          String barcode =
              line.barcode() == null || line.barcode().isBlank()
                  ? ""
                  : " · EAN " + line.barcode();
          y =
              writeFitted(
                  stream,
                  font,
                  9,
                  margin,
                  y,
                  maxWidth,
                  line.skuCode() + " × " + line.qty() + barcode);
          y -= 1 * MM;
          y = writeFitted(stream, font, 8, margin, y, maxWidth, line.name());
          y -= 1 * MM;
          y =
              writeFitted(
                  stream, font, 7, margin, y, maxWidth, "คลัง " + line.warehouseCode());
          y -= 3 * MM;
          if (y < margin + 10 * MM) {
            break;
          }
        }
        if (detail.orders().size() <= 12) {
          y -= 2 * MM;
          y = writeFitted(stream, font, 8, margin, y, maxWidth, "ออเดอร์:");
          for (FulfillmentViews.PickListOrderRef order : detail.orders()) {
            y =
                writeFitted(
                    stream,
                    font,
                    7,
                    margin,
                    y,
                    maxWidth,
                    order.sequence() + 1 + ". " + order.externalOrderId());
            y -= 1 * MM;
          }
        }
      }
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      document.save(out);
      return out.toByteArray();
    } catch (IOException ex) {
      throw new IllegalStateException("pick list PDF failed", ex);
    }
  }

  private static PDFont loadFont(PDDocument document) throws IOException {
    ClassPathResource resource = new ClassPathResource("fonts/IBMPlexSansThai-Regular.ttf");
    try (InputStream input = resource.getInputStream()) {
      return PDType0Font.load(document, input);
    }
  }

  private static float writeFitted(
      PDPageContentStream stream,
      PDFont font,
      float size,
      float x,
      float y,
      float maxWidth,
      String text)
      throws IOException {
    return writeLine(stream, font, size, x, y, fit(font, size, maxWidth, text));
  }

  private static String fit(PDFont font, float size, float maxWidth, String text)
      throws IOException {
    String value = text == null ? "" : text;
    while (value.length() > 1 && stringWidth(font, size, value) > maxWidth) {
      value = value.substring(0, value.length() - 2) + "…";
    }
    return value;
  }

  private static float stringWidth(PDFont font, float size, String text) throws IOException {
    return font.getStringWidth(text) / 1000f * size;
  }

  private static float writeLine(
      PDPageContentStream stream, PDFont font, float size, float x, float y, String text)
      throws IOException {
    stream.beginText();
    stream.setFont(font, size);
    stream.newLineAtOffset(x, y);
    stream.showText(text == null ? "" : text);
    stream.endText();
    return y - size * 1.35f;
  }
}
