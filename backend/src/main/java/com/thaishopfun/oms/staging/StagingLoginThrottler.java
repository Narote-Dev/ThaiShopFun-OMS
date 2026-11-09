package com.thaishopfun.oms.staging;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** In-memory login rate limiting and lockout for the staging demo IdP (single small instance). */
@Component
@Profile("staging")
class StagingLoginThrottler {

  private final StagingProperties properties;
  private final Clock clock;
  private final Map<String, AttemptState> attempts = new ConcurrentHashMap<>();

  StagingLoginThrottler(StagingProperties properties, Clock clock) {
    this.properties = properties;
    this.clock = clock;
  }

  /** Returns empty when allowed; otherwise a human-readable Thai lockout message. */
  Result check(String throttleKey) {
    AttemptState state = attempts.get(throttleKey);
    Instant now = clock.instant();
    if (state == null) {
      return Result.permit();
    }
    if (state.lockedUntil != null && now.isBefore(state.lockedUntil)) {
      return Result.lock("ลองเข้าสู่ระบบบ่อยเกินไป กรุณารอสักครู่แล้วลองใหม่");
    }
    if (state.windowStarted == null
        || Duration.between(state.windowStarted, now).getSeconds()
            > properties.getLoginWindowSeconds()) {
      attempts.remove(throttleKey);
      return Result.permit();
    }
    if (state.attemptsInWindow >= properties.getLoginMaxAttempts()) {
      return Result.lock("ลองเข้าสู่ระบบบ่อยเกินไป กรุณารอสักครู่แล้วลองใหม่");
    }
    return Result.permit();
  }

  void recordFailure(String throttleKey) {
    Instant now = clock.instant();
    attempts.compute(
        throttleKey,
        (key, existing) -> {
          AttemptState state = existing == null ? new AttemptState() : existing;
          if (state.lockedUntil != null && now.isBefore(state.lockedUntil)) {
            return state;
          }
          if (state.windowStarted == null
              || Duration.between(state.windowStarted, now).getSeconds()
                  > properties.getLoginWindowSeconds()) {
            state.windowStarted = now;
            state.attemptsInWindow = 0;
            state.consecutiveFailures = 0;
            state.lockedUntil = null;
          }
          state.attemptsInWindow++;
          state.consecutiveFailures++;
          if (state.consecutiveFailures >= properties.getLoginLockoutThreshold()) {
            state.lockedUntil = now.plusSeconds(properties.getLoginLockoutSeconds());
            state.consecutiveFailures = 0;
          }
          return state;
        });
  }

  void recordSuccess(String throttleKey) {
    attempts.remove(throttleKey);
  }

  record Result(boolean permitted, boolean locked, String message) {
    static Result permit() {
      return new Result(true, false, null);
    }

    static Result lock(String message) {
      return new Result(false, true, message);
    }
  }

  private static final class AttemptState {
    Instant windowStarted;
    int attemptsInWindow;
    int consecutiveFailures;
    Instant lockedUntil;
  }
}
