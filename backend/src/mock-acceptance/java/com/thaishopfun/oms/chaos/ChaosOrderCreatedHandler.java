package com.thaishopfun.oms.chaos;

import com.thaishopfun.oms.auth.UuidV7;
import com.thaishopfun.oms.inbox.InboxHandler;
import com.thaishopfun.oms.inbox.InboxMessage;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Test-only {@code order.created} handler. There is no production handler for this type yet. It is
 * registered only by {@link DeliveryChaosTest}'s test configuration, never by component scan, so no
 * other context (and no {@code local} or production profile) sees it.
 *
 * <p>The effect is one {@code audit_log} row per event, written inside the inbox transaction. A
 * planned failure is thrown after that write, so a surviving row would prove a missing rollback.
 */
final class ChaosOrderCreatedHandler implements InboxHandler {

  static final String ACTION = "CHAOS_TEST";

  enum Plan {
    /** Throw on the first call. The worker records FAILED and backs off. */
    THROW_ONCE,
    /** Throw on the first two calls. */
    THROW_TWICE,
    /** Die on the first call, like a process kill. The worker records nothing. */
    KILL_WORKER
  }

  /**
   * Not a RuntimeException, so {@code InboxWorker} does not catch it and the batch is abandoned.
   */
  static final class WorkerKilled extends Error {
    WorkerKilled(String eventId) {
      super("chaos: inbox worker killed while handling " + eventId);
    }
  }

  private final JdbcTemplate jdbc;
  private final Map<String, Plan> plans = new ConcurrentHashMap<>();
  private final Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();
  private final AtomicInteger handlerErrors = new AtomicInteger();
  private final AtomicInteger kills = new AtomicInteger();

  ChaosOrderCreatedHandler(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public String eventType() {
    return "order.created";
  }

  @Override
  public void handle(InboxMessage message) {
    int call =
        calls.computeIfAbsent(message.eventId(), ignored -> new AtomicInteger()).incrementAndGet();

    // Step 1: The effect. Same transaction as PROCESSED, under the worker's tenant context.
    jdbc.update(
        """
        INSERT INTO audit_log (id, tenant_id, actor_type, action, entity_type, entity_id)
        VALUES (?, ?, 'TSF', ?, 'inbox_event', ?)
        """,
        UuidV7.generate(),
        message.tenantId(),
        ACTION,
        message.eventId());

    // Step 2: Planned failure after the write. Only the rollback can remove the row again.
    Plan plan = plans.get(message.eventId());
    if (plan == Plan.KILL_WORKER && call == 1) {
      kills.incrementAndGet();
      throw new WorkerKilled(message.eventId());
    }
    if ((plan == Plan.THROW_ONCE && call == 1) || (plan == Plan.THROW_TWICE && call <= 2)) {
      handlerErrors.incrementAndGet();
      throw new IllegalStateException("chaos: handler failed");
    }
  }

  void plan(String eventId, Plan plan) {
    plans.put(eventId, plan);
  }

  void reset() {
    plans.clear();
    calls.clear();
    handlerErrors.set(0);
    kills.set(0);
  }

  int calls(String eventId) {
    AtomicInteger count = calls.get(eventId);
    return count == null ? 0 : count.get();
  }

  int totalCalls() {
    return calls.values().stream().mapToInt(AtomicInteger::get).sum();
  }

  int handlerErrors() {
    return handlerErrors.get();
  }

  int kills() {
    return kills.get();
  }
}
