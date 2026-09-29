package com.thaishopfun.oms.outbox;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Section 4.4 retry schedule. Delays are 30s, 2m, 10m, 30m, 1h, 3h, 6h (~11h). The attempt after
 * that delay ladder is {@code DEAD}. Jitter is ±{@code jitterRatio}. A positive {@code Retry-After}
 * longer than the jittered delay wins.
 */
@Component
public class OutboxRetryPolicy {

  static final Duration[] BACKOFF =
      new Duration[] {
        Duration.ofSeconds(30),
        Duration.ofMinutes(2),
        Duration.ofMinutes(10),
        Duration.ofMinutes(30),
        Duration.ofHours(1),
        Duration.ofHours(3),
        Duration.ofHours(6)
      };

  private final double jitterRatio;
  private final DoubleSupplier random;

  @Autowired
  public OutboxRetryPolicy(OutboxProperties properties) {
    this(properties.getJitterRatio(), () -> ThreadLocalRandom.current().nextDouble());
  }

  OutboxRetryPolicy(double jitterRatio, DoubleSupplier random) {
    if (jitterRatio < 0 || jitterRatio >= 1) {
      throw new IllegalArgumentException("jitter ratio must be in [0, 1)");
    }
    this.jitterRatio = jitterRatio;
    this.random = random;
  }

  /**
   * @param attempts value already incremented by {@code claim_outbox_batch} (1 on the first try)
   * @return empty when this failure is terminal
   */
  public Optional<Duration> nextDelay(int attempts, Duration retryAfter) {
    // Step 1: The ladder has seven waits. The eighth failure is DEAD.
    if (attempts < 1 || attempts > BACKOFF.length) {
      return Optional.empty();
    }
    // Step 2: Spread the slot by ±jitter so publishers do not retry in lockstep.
    Duration base = BACKOFF[attempts - 1];
    double sample = random.getAsDouble();
    double multiplier = (1 - jitterRatio) + (2 * jitterRatio * sample);
    Duration jittered = Duration.ofMillis(Math.round(base.toMillis() * multiplier));
    // Step 3: Honor Retry-After when it is later than the schedule. Do not shorten it.
    if (retryAfter != null
        && retryAfter.compareTo(Duration.ZERO) > 0
        && retryAfter.compareTo(jittered) > 0) {
      return Optional.of(retryAfter);
    }
    return Optional.of(jittered);
  }
}
