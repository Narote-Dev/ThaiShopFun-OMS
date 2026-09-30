package com.thaishopfun.oms.stock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

/** Unit level: the 60 s schedule returns every expired hold within the plan's 2 minutes. */
class StockExpirySchedulerTest {

  @Test
  void tickRunsEverySixtySecondsOnItsOwnPool() throws NoSuchMethodException {
    Scheduled scheduled =
        StockExpiryScheduler.class.getMethod("tick").getAnnotation(Scheduled.class);
    assertThat(scheduled.fixedRateString()).isEqualTo("${oms.stock.expiry.interval-ms:60000}");
    assertThat(scheduled.scheduler()).isEqualTo("stockTaskScheduler");
    assertThat(new StockProperties().getExpiry().getIntervalMs()).isEqualTo(60_000);
  }

  @Test
  void intervalAboveSixtySecondsIsRejected() {
    StockProperties properties = new StockProperties();
    properties.validate();
    properties.getExpiry().setIntervalMs(60_001);
    assertThatThrownBy(properties::validate).hasMessageContaining("interval-ms");
    properties.getExpiry().setIntervalMs(60_000);
    properties.getRetry().setMaxRetries(4);
    assertThatThrownBy(properties::validate).hasMessageContaining("max-retries");
  }

  @Test
  void worstCaseReturnIsUnderTwoMinutes() {
    // Step 1: Ticks start every interval (fixed rate). Each run uses its start time as p_now and
    // takes up to runBudget. A hold is returned by the first run whose p_now >= expires_at.
    long interval = new StockProperties().getExpiry().getIntervalMs();
    long runBudget = Duration.ofSeconds(30).toMillis();
    long worst = 0;
    for (long expiresAt = 0; expiresAt <= 10 * interval; expiresAt += 250) {
      long tick = ((expiresAt + interval - 1) / interval) * interval;
      worst = Math.max(worst, tick + runBudget - expiresAt);
    }
    // Step 2: Worst case is one interval plus one run, under 2 minutes.
    assertThat(worst).isLessThanOrEqualTo(interval + runBudget);
    assertThat(worst).isLessThan(Duration.ofMinutes(2).toMillis());
  }
}
