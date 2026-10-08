package com.thaishopfun.oms.invariant;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import org.slf4j.LoggerFactory;

/** Captures log lines in tests; asserts sentinel PII strings never appear (T27 will widen this). */
public final class PiiLogAssertions implements AutoCloseable {

  public static final String SENTINEL_PHONE = "0812345678";
  public static final String SENTINEL_EMAIL = "customer-pii@example.com";
  public static final String SENTINEL_NAME = "สมชาย ใจดี";

  private final ListAppender<ILoggingEvent> appender;

  private PiiLogAssertions(ListAppender<ILoggingEvent> appender) {
    this.appender = appender;
  }

  public static PiiLogAssertions attach(String loggerName) {
    Logger logger = (Logger) LoggerFactory.getLogger(loggerName);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    return new PiiLogAssertions(appender);
  }

  public void assertNoPii() {
    for (ILoggingEvent event : appender.list) {
      String line = event.getFormattedMessage();
      assertThat(line).doesNotContain(SENTINEL_PHONE);
      assertThat(line).doesNotContain(SENTINEL_EMAIL);
      assertThat(line).doesNotContain(SENTINEL_NAME);
    }
  }

  public List<String> messages() {
    return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
  }

  @Override
  public void close() {
    appender.stop();
  }
}
