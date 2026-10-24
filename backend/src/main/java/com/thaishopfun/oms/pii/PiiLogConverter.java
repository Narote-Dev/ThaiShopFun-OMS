package com.thaishopfun.oms.pii;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.pattern.CompositeConverter;

/** Wraps the existing complete layout, including nested exception/cause/suppressed text. */
public class PiiLogConverter extends CompositeConverter<ILoggingEvent> {
  @Override
  protected String transform(ILoggingEvent event, String text) {
    return PiiLogMask.mask(text);
  }
}
