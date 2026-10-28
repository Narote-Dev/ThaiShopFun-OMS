package com.thaishopfun.oms.fulfillment;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.multipdf.PDFMergerUtility;
import org.springframework.stereotype.Component;

/** Merges up to 100 label PDFs into one document (one page per source page). */
@Component
public class LabelBatchPdfRenderer {

  static final int MAX_LABELS = 100;

  public byte[] merge(List<byte[]> labelPdfs) {
    if (labelPdfs.isEmpty()) {
      throw new IllegalArgumentException("labelPdfs must not be empty");
    }
    if (labelPdfs.size() > MAX_LABELS) {
      throw new IllegalArgumentException("At most " + MAX_LABELS + " labels per batch");
    }
    PDFMergerUtility merger = new PDFMergerUtility();
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    merger.setDestinationStream(out);
    try {
      for (byte[] pdf : labelPdfs) {
        merger.addSource(new RandomAccessReadBuffer(pdf));
      }
      merger.mergeDocuments(null);
      return out.toByteArray();
    } catch (IOException ex) {
      throw new IllegalStateException("Failed to merge label PDFs", ex);
    }
  }
}
