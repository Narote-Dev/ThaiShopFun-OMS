package com.thaishopfun.oms.stock;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** Test clock, fault seam, and an after-commit {@link StockChanged} recorder. */
@TestConfiguration
class StockTestConfig {

  @Bean
  @Primary
  MutableClock stockTestClock() {
    // A day ahead of real time and only moving forward, so no other clock sees these holds expire.
    return new MutableClock(Instant.now().plus(Duration.ofDays(1)).truncatedTo(ChronoUnit.MICROS));
  }

  @Bean
  @Primary
  FaultHooks faultHooks(JdbcTemplate jdbc) {
    return new FaultHooks(jdbc);
  }

  @Bean
  StockEventRecorder stockEventRecorder() {
    return new StockEventRecorder();
  }

  static final class MutableClock extends Clock {

    private volatile Instant now;

    MutableClock(Instant start) {
      this.now = start;
    }

    void advance(Duration duration) {
      now = now.plus(duration);
    }

    @Override
    public Instant instant() {
      return now;
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }
  }

  enum Fault {
    /** A plain exception after the inventory lock: the transaction rolls back, no retry. */
    THROW,
    /** A real server-side 40001: the engine retries the whole transaction. */
    SERIALIZATION,
    /** A real server-side 40P01. */
    DEADLOCK
  }

  /** One-shot fault on the next engine write, fired once its inventory rows are locked. */
  static final class FaultHooks extends StockHooks {

    private final JdbcTemplate jdbc;
    private final AtomicReference<Fault> next = new AtomicReference<>();
    private final AtomicReference<Runnable> beforeInventoryLock = new AtomicReference<>();
    private final AtomicReference<Runnable> atInventoryLock = new AtomicReference<>();
    private final java.util.concurrent.atomic.AtomicInteger fired =
        new java.util.concurrent.atomic.AtomicInteger();

    FaultHooks(JdbcTemplate jdbc) {
      this.jdbc = jdbc;
    }

    void failNext(Fault fault) {
      next.set(fault);
    }

    /** Runs once on the next write, before inventory rows are locked. */
    void atNextBeforeInventoryLock(Runnable action) {
      beforeInventoryLock.set(action);
    }

    /**
     * Runs once on the next write, after inventory rows are locked and before any injected fault.
     */
    void atNextInventoryLock(Runnable action) {
      atInventoryLock.set(action);
    }

    void reset() {
      next.set(null);
      beforeInventoryLock.set(null);
      atInventoryLock.set(null);
    }

    int fired() {
      return fired.get();
    }

    @Override
    void beforeInventoryLock(String operation) {
      Runnable pause = beforeInventoryLock.getAndSet(null);
      if (pause != null) {
        pause.run();
      }
    }

    @Override
    void afterInventoryLocked(String operation) {
      Runnable pause = atInventoryLock.getAndSet(null);
      if (pause != null) {
        pause.run();
      }
      Fault fault = next.getAndSet(null);
      if (fault == null) {
        return;
      }
      fired.incrementAndGet();
      switch (fault) {
        case THROW -> throw new IllegalStateException("injected failure in " + operation);
        case SERIALIZATION -> raise("serialization_failure");
        case DEADLOCK -> raise("deadlock_detected");
        default -> throw new IllegalStateException("unknown fault");
      }
    }

    private void raise(String condition) {
      jdbc.execute(
          "DO $$ BEGIN RAISE EXCEPTION USING ERRCODE = '"
              + condition
              + "', MESSAGE = 'injected "
              + condition
              + "'; END $$");
    }
  }

  static final class StockEventRecorder {

    private final List<StockChanged> events = new CopyOnWriteArrayList<>();

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void onStockChanged(StockChanged event) {
      events.add(event);
    }

    List<StockChanged> forTenant(UUID tenantId) {
      return events.stream().filter(event -> event.tenantId().equals(tenantId)).toList();
    }
  }
}
