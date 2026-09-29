package com.thaishopfun.mocktsf.events;

import java.util.Locale;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

/**
 * Arms the next N calls to one section 4.7 route. Each matching call consumes one arm and the
 * filter answers 429 or 503 before the controller runs.
 */
@Component
public class FaultSchedule {

  public record Armed(int status, Integer retryAfter) {}

  private record Arm(
      String method, String path, int status, AtomicInteger remaining, Integer retryAfter) {}

  private final ConcurrentLinkedQueue<Arm> arms = new ConcurrentLinkedQueue<>();

  public void arm(String method, String path, int status, int times, Integer retryAfter) {
    arms.add(
        new Arm(
            method.toUpperCase(Locale.ROOT), path, status, new AtomicInteger(times), retryAfter));
  }

  public int pending() {
    return arms.size();
  }

  /** One matching call. The arm leaves the queue when its count hits zero. */
  public Armed consume(String method, String path) {
    String normalized = method == null ? "" : method.toUpperCase(Locale.ROOT);
    for (Arm arm : arms) {
      if (!arm.method.equals(normalized) || !arm.path.equals(path)) {
        continue;
      }
      int left = arm.remaining.getAndUpdate(count -> count > 0 ? count - 1 : 0);
      if (left <= 0) {
        arms.remove(arm);
        continue;
      }
      if (left == 1) {
        arms.remove(arm);
      }
      return new Armed(arm.status, arm.retryAfter);
    }
    return null;
  }
}
