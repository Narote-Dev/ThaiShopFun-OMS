package com.thaishopfun.oms.order.hold;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class OrderHoldRetryRepositoryTest {

  @Test
  void scheduleNextDoublesBackoffUntilCap() {
    OrderHoldProperties properties = new OrderHoldProperties();
    properties.setBackoffBase(Duration.ofMinutes(1));
    properties.setBackoffMax(Duration.ofHours(1));
    properties.setBackoffJitter(0);
    OrderHoldRetryRepository repository =
        new OrderHoldRetryRepository(new JdbcTemplate(), properties);
    Instant now = Instant.parse("2026-01-01T00:00:00Z");

    Instant afterFirst = repository.scheduleNext(1, now);
    assertThat(afterFirst).isEqualTo(now.plus(Duration.ofMinutes(1)));

    Instant afterSecond = repository.scheduleNext(2, now);
    assertThat(afterSecond).isEqualTo(now.plus(Duration.ofMinutes(2)));

    Instant capped = repository.scheduleNext(100, now);
    assertThat(capped).isEqualTo(now.plus(Duration.ofHours(1)));
  }
}
