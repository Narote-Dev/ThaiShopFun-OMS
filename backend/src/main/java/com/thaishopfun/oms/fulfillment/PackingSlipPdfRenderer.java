package com.thaishopfun.oms.fulfillment;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/** A6 packing slip when TSF does not issue a carrier label (manual tracking). */
@Component
public class PackingSlipPdfRenderer {

  private static final float MM = 72f / 25.4f;
  private static final PDRectangle A6 = new PDRectangle(105 * MM, 148 * MM);
  private static final ZoneId BANGKOK = ZoneId.of("Asia/Bangkok");
  private static final DateTimeFormatter WHEN =
      DateTimeFormatter.ofPattern("d MMM yyyy HH:mm").withZone(BANGKOK);

  public byte[] renderA6(FulfillmentViews.PackingSlipData data) {
    try (PDDocument document = new PDDocument()) {
      PDFont font = loadFont(document);
      PDPage page = new PDPage(A6);
      document.addPage(page);
      float margin = 8 * MM;
      float maxWidth = A6.getWidth() - 2 * margin;
      float y = A6.getHeight() - margin;
      try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
        y = writeFitted(stream, font, 14, margin, y, maxWidth, "ใบแพ็ก (ไม่มีใบปะหน้า TSF)");
        y -= 3 * MM;
        y = writeFitted(stream, font, 9, margin, y, maxWidth, "ออเดอร์ " + data.externalOrderId());
        y -= 2 * MM;
        y =
            writeFitted(
                stream,
                font,
                9,
                margin,
                y,
                maxWidth,
                "ขนส่ง " + data.carrier() + " · " + data.trackingNo());
        y -= 2 * MM;
        y = writeFitted(stream, font, 9, margin, y, maxWidth, "ถึง " + data.recipientName());
        y -= 2 * MM;
        y = writeFitted(stream, font, 8, margin, y, maxWidth, data.recipientAddress());
        y -= 2 * MM;
        y =
            writeFitted(
                stream,
                font,
                8,
                margin,
                y,
                maxWidth,
                "จังหวัด " + data.province() + " · รหัสไปรษณีย์ " + data.postcode());
        y -= 4 * MM;
        y = writeFitted(stream, font, 10, margin, y, maxWidth, "รายการ");
        y -= 2 * MM;
        for (FulfillmentViews.PackingSlipLine line : data.lines()) {
          y =
              writeFitted(
                  stream,
                  font,
                  9,
                  margin,
                  y,
                  maxWidth,
                  line.skuCode() + " × " + line.qty() + " · " + line.name());
          y -= 2 * MM;
        }
        y -= 2 * MM;
        writeFitted(stream, font, 7, margin, y, maxWidth, WHEN.format(data.generatedAt()));
      }
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      document.save(out);
      return out.toByteArray();
    } catch (IOException ex) {
      throw new IllegalStateException("Failed to render packing slip PDF", ex);
    }
  }

  private static PDFont loadFont(PDDocument document) throws IOException {
    ClassPathResource resource = new ClassPathResource("fonts/IBMPlexSansThai-Regular.ttf");
    try (InputStream stream = resource.getInputStream()) {
      return PDType0Font.load(document, stream);
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
    String line = text == null ? "" : text;
    stream.beginText();
    stream.setFont(font, size);
    stream.newLineAtOffset(x, y);
    stream.showText(line);
    stream.endText();
    return y - size * 1.4f;
  }
}
