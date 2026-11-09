package com.thaishopfun.oms.staging;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class StagingLoginThrottlerTest {

  @Test
  void locksOutAfterRepeatedFailures() {
    StagingProperties properties = new StagingProperties();
    properties.setLoginLockoutThreshold(3);
    properties.setLoginLockoutSeconds(60);
    properties.setLoginMaxAttempts(100);
    properties.setLoginWindowSeconds(900);
    MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
    StagingLoginThrottler throttler = new StagingLoginThrottler(properties, clock);
    String key = "203.0.113.1";
    assertThat(throttler.check(key).permitted()).isTrue();
    throttler.recordFailure(key);
    throttler.recordFailure(key);
    throttler.recordFailure(key);
    assertThat(throttler.check(key).locked()).isTrue();
    clock.advanceSeconds(30);
    assertThat(throttler.check(key).locked()).isTrue();
    clock.advanceSeconds(40);
    assertThat(throttler.check(key).permitted()).isTrue();
  }

  private static final class MutableClock extends Clock {
    private Instant instant;

    MutableClock(Instant start) {
      this.instant = start;
    }

    void advanceSeconds(long seconds) {
      instant = instant.plusSeconds(seconds);
    }

    @Override
    public ZoneOffset getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return instant;
    }
  }
}
