package com.thaishopfun.oms.invariant;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import org.slf4j.LoggerFactory;

/** Captures log lines in tests; asserts known PII from fixtures and contracts never appear. */
public final class PiiLogAssertions implements AutoCloseable {

  /** Legacy sentinel; kept for tests that embed this value explicitly. */
  public static final String SENTINEL_PHONE = "0812345678";

  public static final String SENTINEL_EMAIL = "customer-pii@example.com";
  public static final String SENTINEL_NAME = "สมชาย ใจดี";

  /** Values used by order intake examples, OrderFixture, and related API tests (T00 AC6). */
  private static final List<String> FORBIDDEN =
      List.of(
          SENTINEL_PHONE,
          SENTINEL_EMAIL,
          SENTINEL_NAME,
          "0812341234",
          "66812341234",
          "081-234-5678",
          "+66812345678",
          "66812345678",
          "812345678");

  private final Logger logger;
  private final ListAppender<ILoggingEvent> appender;

  private PiiLogAssertions(Logger logger, ListAppender<ILoggingEvent> appender) {
    this.logger = logger;
    this.appender = appender;
  }

  public static PiiLogAssertions attach(String loggerName) {
    Logger logger = (Logger) LoggerFactory.getLogger(loggerName);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    return new PiiLogAssertions(logger, appender);
  }

  public void assertNoPii() {
    for (ILoggingEvent event : appender.list) {
      String line = event.getFormattedMessage();
      for (String secret : FORBIDDEN) {
        assertThat(line).doesNotContain(secret);
      }
    }
  }

  public List<String> messages() {
    return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
  }

  @Override
  public void close() {
    logger.detachAppender(appender);
    appender.stop();
  }
}
