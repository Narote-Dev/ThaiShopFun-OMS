package com.thaishopfun.oms.pii;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.core.OutputStreamAppender;
import java.io.ByteArrayOutputStream;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

class PiiLogMaskTest {
  @ParameterizedTest
  @ValueSource(
      strings = {
        "0812341234",
        "081-234-1234",
        "081 234 1234",
        "021234567",
        "+66812341234",
        "+66 81 234 1234",
        "customer-pii@example.com"
      })
  void masksEachPhoneAndEmailPattern(String value) {
    assertThat(PiiLogMask.mask("contact: " + value)).isEqualTo("contact: [PII]");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "name",
        "phone",
        "email",
        "address",
        "line1",
        "line2",
        "district",
        "subdistrict",
        "recipient",
        "recipient_name",
        "recipient_phone",
        "recipient_address",
        "first_name",
        "last_name",
        "full_name",
        "street",
        "PHONE",
        "note"
      })
  void masksJsonKeysAndEscapedValues(String key) {
    assertThat(PiiLogMask.mask("{\"" + key + "\":\"private \\\"value\\\"\",\"id\":\"keep\"}"))
        .isEqualTo("{\"" + key + "\":\"[PII]\",\"id\":\"keep\"}");
  }

  @Test
  void masksWholeNestedRecipientAndAddressWithoutLeakingUnknownKeys() {
    assertThat(
            PiiLogMask.mask(
                "{\"recipient\":{\"custom\":[\"private\",{\"x\":\"secret\"}]},\"id\":7}"))
        .isEqualTo("{\"recipient\":\"[PII]\",\"id\":7}");
    assertThat(PiiLogMask.mask("{\"address\":{\"nested\":\"a } \\\" b\"},\"id\":7}"))
        .isEqualTo("{\"address\":\"[PII]\",\"id\":7}");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "name", "customerName", "recipient", "shipping_address", "PHONE", "contactEmail",
        "tel", "mobileNumber", "ชื่อ", "ชื่อผู้รับ", "ที่อยู่จัดส่ง", "เบอร์โทร"
      })
  void masksDifferentRandomValuesUnderJsonAndPairKeys(String key) {
    for (int i = 0; i < 4; i++) {
      String value = "Person " + UUID.randomUUID() + " Road " + UUID.randomUUID();
      assertThat(PiiLogMask.mask("{\"" + key + "\":\"" + value + "\",\"id\":7}"))
          .isEqualTo("{\"" + key + "\":\"[PII]\",\"id\":7}");
      for (String separator : new String[] {"=", ": "}) {
        assertThat(PiiLogMask.mask(key + separator + value + " id=7"))
            .isEqualTo(key + separator + "[PII] id=7");
      }
    }
  }

  @Test
  void masksPairsWithQuotedValuesAndDelimiters() {
    assertThat(PiiLogMask.mask("name='Alice Example', address=42 Orchard Lane; id=7"))
        .isEqualTo("name='[PII]', address=[PII]; id=7");
    assertThat(PiiLogMask.mask("recipientName=\"Alice \\\"A\\\" Example\" mobile=123 id=7"))
        .isEqualTo("recipientName=\"[PII]\" mobile=[PII] id=7");
    assertThat(PiiLogMask.mask("name=Alice Example\naddress: 42 Orchard Lane\nid=7"))
        .isEqualTo("name=[PII]\naddress: [PII]\nid=7");
    assertThat(PiiLogMask.mask("{\"mobile\":12345,\"id\":7}"))
        .isEqualTo("{\"mobile\":\"[PII]\",\"id\":7}");
    assertThat(PiiLogMask.mask("{\"shippingAddress\":[{\"custom\":\"secret\"}],\"id\":7}"))
        .isEqualTo("{\"shippingAddress\":\"[PII]\",\"id\":7}");
  }

  @Test
  void leavesUnkeyedNamesAndAddressesAndNonPiiKeysUnchanged() {
    String line = "Alice Example lives at 42 Orchard Lane";
    assertThat(PiiLogMask.mask(line)).isEqualTo(line);
    assertThat(PiiLogMask.mask("event=processed attempts=1 id=abc"))
        .isEqualTo("event=processed attempts=1 id=abc");
  }

  @Test
  void productionMaskerContainsNoSampleLiterals() throws Exception {
    String source =
        Files.readString(Path.of("src/main/java/com/thaishopfun/oms/pii/PiiLogMask.java"));
    assertThat(source)
        .doesNotContain(
            "สมชาย",
            "ใจดี",
            "99/1",
            "ถ.สุขุมวิท",
            "ซอยตัวอย่าง",
            "0812341234",
            "customer-pii@example.com");
  }

  @Test
  void existingLayoutMasksMessageAndNestedExceptionText() {
    LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
    var logger = context.getLogger("pii-deliberate-log");
    var encoder = new PatternLayoutEncoder();
    encoder.setContext(context);
    encoder.setPattern("%pii(%msg%n%ex){}%nopex");
    encoder.start();
    var bytes = new ByteArrayOutputStream();
    var appender = new OutputStreamAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
    appender.setContext(context);
    appender.setEncoder(encoder);
    appender.setOutputStream(bytes);
    appender.start();
    logger.addAppender(appender);
    try {
      var cause = new IllegalArgumentException("email customer-pii@example.com");
      cause.addSuppressed(new RuntimeException("address=99/1 ถ.สุขุมวิท"));
      logger.error("phone 0812341234 name=สมชาย ใจดี", new RuntimeException("081-234-1234", cause));
      assertThat(bytes.toString(StandardCharsets.UTF_8))
          .contains("[PII]", "Suppressed:", "Caused by:")
          .doesNotContain(
              "0812341234",
              "081-234-1234",
              "สมชาย ใจดี",
              "99/1 ถ.สุขุมวิท",
              "customer-pii@example.com");
    } finally {
      logger.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  void suiteGateDetectsFixturePiiEvenAcrossUtf8WriteBoundaries() {
    var capture = new PiiLogGate.Capture();
    byte[] secret = "สมชาย ใจดี".getBytes(StandardCharsets.UTF_8);
    capture.accept(secret, 0, 4);
    assertThat(capture.leaked).isFalse();
    capture.accept(secret, 4, secret.length - 4);
    assertThat(capture.leaked).isTrue();
  }

  @Test
  void normalLineOverheadIsUnderTenMicroseconds() {
    String[] lines = {
      "inbox processed event=event-abc attempts=1",
      "order lookup id=019abc version=2",
      "{\"phone\":\"0812341234\",\"id\":\"order-abc\"}"
    };
    ThreadMXBean threads = ManagementFactory.getThreadMXBean();
    assertThat(threads.isCurrentThreadCpuTimeSupported())
        .as("thread CPU timing supported")
        .isTrue();
    assertThat(threads.isThreadCpuTimeEnabled()).as("thread CPU timing enabled").isTrue();
    // Budget CPU work, not descheduling/GC pauses imposed by a shared CI runner. Warm up
    // the same mixed workload, then take the median batch to limit JIT/GC noise.
    int iterations = 100_000;
    int chars = maskLines(lines, iterations);
    long[] samples = new long[7];
    for (int batch = 0; batch < samples.length; batch++) {
      long start = threads.getCurrentThreadCpuTime();
      chars += maskLines(lines, iterations);
      samples[batch] = threads.getCurrentThreadCpuTime() - start;
      assertThat(samples[batch]).isPositive();
    }
    assertThat(chars).isPositive();
    Arrays.sort(samples);
    double medianNanosPerLine = samples[samples.length / 2] / (double) iterations;
    assertThat(medianNanosPerLine)
        .as(
            "median thread CPU ns/line over %s batches; budget remains 10 microseconds",
            samples.length)
        .isLessThan(10_000);
    String simple = "inbox processed event=abc attempts=1";
    assertThat(PiiLogMask.mask(simple)).isSameAs(simple);
  }

  private static int maskLines(String[] lines, int iterations) {
    int chars = 0;
    for (int i = 0; i < iterations; i++) {
      chars += PiiLogMask.mask(lines[i % lines.length]).length();
    }
    return chars;
  }
}
