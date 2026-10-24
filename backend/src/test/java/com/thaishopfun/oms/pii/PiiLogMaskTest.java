package com.thaishopfun.oms.pii;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.core.OutputStreamAppender;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
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
      cause.addSuppressed(new RuntimeException("99/1 ถ.สุขุมวิท"));
      logger.error("phone 0812341234 สมชาย ใจดี", new RuntimeException("081-234-1234", cause));
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
    for (int i = 0; i < 30_000; i++) PiiLogMask.mask(lines[i % lines.length]);
    long start = System.nanoTime();
    int chars = 0;
    for (int i = 0; i < 100_000; i++) chars += PiiLogMask.mask(lines[i % lines.length]).length();
    long nanos = System.nanoTime() - start;
    assertThat(chars).isPositive();
    assertThat(nanos / 100_000.0).isLessThan(10_000);
    String simple = "inbox processed event=abc attempts=1";
    assertThat(PiiLogMask.mask(simple)).isSameAs(simple);
  }
}
