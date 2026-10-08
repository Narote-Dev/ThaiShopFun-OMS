package com.thaishopfun.oms.invariant;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

class InvariantSchedulerTest {

  @Test
  void cronUsesInvariantSchedulerPool() throws NoSuchMethodException {
    Scheduled scheduled = InvariantScheduler.class.getMethod("tick").getAnnotation(Scheduled.class);
    assertThat(scheduled.cron()).contains("oms.invariant.cron");
    assertThat(scheduled.zone()).contains("Asia/Bangkok");
    assertThat(scheduled.scheduler()).isEqualTo("invariantTaskScheduler");
  }
}
