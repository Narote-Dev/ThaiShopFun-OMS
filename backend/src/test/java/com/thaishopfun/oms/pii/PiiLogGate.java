package com.thaishopfun.oms.pii;

import static org.assertj.core.api.Assertions.assertThat;

import com.thaishopfun.oms.invariant.PiiLogAssertions;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/** Suite-wide grep of actual console bytes, including exceptions and third-party logs. */
public class PiiLogGate implements BeforeAllCallback, AfterEachCallback, AfterAllCallback {
  private static final List<String> SENTINELS =
      List.of(
          PiiLogAssertions.SENTINEL_NAME,
          "0812341234",
          "99/1 ถ.สุขุมวิท",
          PiiLogAssertions.SENTINEL_PHONE,
          "081-234-5678",
          "+66812345678",
          "66812345678",
          "66812341234",
          "812345678",
          PiiLogAssertions.SENTINEL_EMAIL,
          "ซอยตัวอย่าง 1",
          "Recipient A",
          "Recipient B",
          "Recipient V2",
          "Updated Name",
          "New Legal Name",
          "0899999999",
          "0891112233",
          "0811111111",
          "0822222222",
          "0833333333");
  private static final Capture OUT = new Capture();
  private static final Capture ERR = new Capture();
  private static boolean installed;

  @Override
  public void beforeAll(ExtensionContext context) {
    install();
  }

  static void install() {
    synchronized (PiiLogGate.class) {
      if (!installed) {
        System.setOut(tee(System.out, OUT));
        System.setErr(tee(System.err, ERR));
        installed = true;
      }
    }
  }

  @Override
  public void afterEach(ExtensionContext context) {
    assertThat(OUT.leaked || ERR.leaked)
        .as("PII sentinel found in backend suite console output")
        .isFalse();
  }

  @Override
  public void afterAll(ExtensionContext context) {
    afterEach(context);
  }

  private static PrintStream tee(PrintStream original, Capture capture) {
    return new PrintStream(
        new OutputStream() {
          @Override
          public void write(int value) {
            write(new byte[] {(byte) value}, 0, 1);
          }

          @Override
          public void write(byte[] bytes, int offset, int length) {
            original.write(bytes, offset, length);
            capture.accept(bytes, offset, length);
          }

          @Override
          public void flush() {
            original.flush();
          }
        },
        true,
        StandardCharsets.UTF_8);
  }

  static final class Capture {
    private final java.io.ByteArrayOutputStream tail = new java.io.ByteArrayOutputStream();
    volatile boolean leaked;

    synchronized void accept(byte[] bytes, int offset, int length) {
      tail.write(bytes, offset, length);
      String text = tail.toString(StandardCharsets.UTF_8);
      for (String sentinel : SENTINELS) if (text.contains(sentinel)) leaked = true;
      if (tail.size() > 4096) {
        byte[] all = tail.toByteArray();
        tail.reset();
        tail.write(all, all.length - 512, 512);
      }
    }
  }
}
