package com.thaishopfun.oms.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class OutboxRetryPolicyTest {

  @Test
  void scheduleMatchesSection44WithJitter() {
    OutboxRetryPolicy low = new OutboxRetryPolicy(0.2, () -> 0.0);
    OutboxRetryPolicy mid = new OutboxRetryPolicy(0.2, () -> 0.5);
    OutboxRetryPolicy high = new OutboxRetryPolicy(0.2, () -> 0.999);

    assertThat(low.nextDelay(1, null)).contains(Duration.ofSeconds(24));
    assertThat(mid.nextDelay(1, null)).contains(Duration.ofSeconds(30));
    assertThat(high.nextDelay(1, null).orElseThrow())
        .isBetween(Duration.ofMillis(35900), Duration.ofSeconds(36));
    assertThat(mid.nextDelay(2, null)).contains(Duration.ofMinutes(2));
    assertThat(mid.nextDelay(3, null)).contains(Duration.ofMinutes(10));
    assertThat(mid.nextDelay(4, null)).contains(Duration.ofMinutes(30));
    assertThat(mid.nextDelay(5, null)).contains(Duration.ofHours(1));
    assertThat(mid.nextDelay(6, null)).contains(Duration.ofHours(3));
    assertThat(mid.nextDelay(7, null)).contains(Duration.ofHours(6));
    assertThat(mid.nextDelay(8, null)).isEmpty();
  }

  @Test
  void retryAfterWinsOnlyWhenLaterThanTheSchedule() {
    OutboxRetryPolicy policy = new OutboxRetryPolicy(0.2, () -> 0.5);
    assertThat(policy.nextDelay(1, Duration.ofSeconds(5))).contains(Duration.ofSeconds(30));
    assertThat(policy.nextDelay(1, Duration.ofSeconds(50))).contains(Duration.ofSeconds(50));
    assertThat(policy.nextDelay(1, Duration.ZERO)).contains(Duration.ofSeconds(30));
    assertThat(policy.nextDelay(8, Duration.ofHours(5))).isEqualTo(Optional.empty());
  }

  @Test
  void jitterRatioMustStayBelowOne() {
    assertThatThrownBy(() -> new OutboxRetryPolicy(1, () -> 0.0))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
