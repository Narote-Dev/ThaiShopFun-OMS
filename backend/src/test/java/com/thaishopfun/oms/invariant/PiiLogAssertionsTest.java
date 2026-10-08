package com.thaishopfun.oms.invariant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class PiiLogAssertionsTest {

  private static final Logger LOG = LoggerFactory.getLogger(PiiLogAssertionsTest.class);

  @Test
  void assertNoPiiPassesWhenLogsAreClean() {
    try (PiiLogAssertions logs = PiiLogAssertions.attach(PiiLogAssertionsTest.class.getName())) {
      LOG.info("order intake completed external_id=TSF-1");
      logs.assertNoPii();
    }
  }

  @Test
  void assertNoPiiFailsWhenIntakePhoneAppearsInLogs() {
    try (PiiLogAssertions logs = PiiLogAssertions.attach(PiiLogAssertionsTest.class.getName())) {
      LOG.info("debug leak phone=0812341234");
      assertThatThrownBy(logs::assertNoPii)
          .isInstanceOf(AssertionError.class)
          .satisfies(err -> assertThat(err.getMessage()).contains("0812341234"));
    }
  }
}
