package com.thaishopfun.oms.staging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class StagingLoginThrottlerXffTest {

  @Test
  void clientIpIgnoresSpoofedXForwardedFor() {
    HttpServletRequest request = Mockito.mock(HttpServletRequest.class);
    when(request.getRemoteAddr()).thenReturn("203.0.113.10");
    when(request.getHeader("X-Forwarded-For")).thenReturn("198.51.100.99");
    assertThat(StagingClientIp.resolve(request)).isEqualTo("203.0.113.10");
  }

  @Test
  void loginLockoutIgnoresDifferentIpBuckets() {
    StagingProperties properties = new StagingProperties();
    properties.setLoginLockoutThreshold(2);
    properties.setLoginLockoutSeconds(300);
    properties.setLoginMaxAttempts(100);
    properties.setLoginWindowSeconds(900);
    MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
    StagingLoginThrottler throttler = new StagingLoginThrottler(properties, clock);
    String loginKey = StagingLoginThrottleKeys.forLogin("owner-active");
    throttler.recordFailure(loginKey);
    throttler.recordFailure(loginKey);
    assertThat(throttler.check(loginKey).locked()).isTrue();
    assertThat(throttler.check(StagingLoginThrottleKeys.forIp("203.0.113.10")).permitted())
        .isTrue();
    assertThat(throttler.check(StagingLoginThrottleKeys.forIp("198.51.100.99")).permitted())
        .isTrue();
    assertThat(throttler.check(loginKey).locked()).isTrue();
  }

  @Test
  void separateUsernamesDoNotShareLockoutBucket() {
    StagingProperties properties = new StagingProperties();
    properties.setLoginLockoutThreshold(2);
    properties.setLoginLockoutSeconds(300);
    properties.setLoginMaxAttempts(100);
    properties.setLoginWindowSeconds(900);
    StagingLoginThrottler throttler =
        new StagingLoginThrottler(properties, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
    String userA = StagingLoginThrottleKeys.forLogin("owner-active");
    String userB = StagingLoginThrottleKeys.forLogin("staff-active");
    throttler.recordFailure(userA);
    throttler.recordFailure(userA);
    assertThat(throttler.check(userA).locked()).isTrue();
    assertThat(throttler.check(userB).permitted()).isTrue();
  }

  private static final class MutableClock extends Clock {
    private Instant instant;

    MutableClock(Instant start) {
      this.instant = start;
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
