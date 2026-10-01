package com.thaishopfun.oms.stock;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.SQLException;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Retries a whole engine transaction on {@code 40P01} (deadlock_detected), {@code 40001}
 * (serialization_failure), and a conditional UPDATE that missed under the lock. Bounded: at most
 * {@code max-retries} (3) retries, exponential backoff with jitter (10, 40, 160 ms nominal,
 * capped). Exhausted retries and {@code 55P03} (lock_timeout) become {@link StockBusyException}.
 * Business errors and constraint violations are never retried.
 *
 * <p>Must be called outside any transaction: a retry inside an open transaction would re-run
 * statements on a connection whose transaction Postgres has already aborted.
 */
@Component
public class StockRetry {

  public static final String RETRY_METRIC = "oms.stock.retry";
  public static final String BUSY_METRIC = "oms.stock.busy";

  private static final Logger log = LoggerFactory.getLogger(StockRetry.class);

  enum Cause {
    DEADLOCK("deadlock"),
    SERIALIZATION("serialization"),
    CONDITIONAL_UPDATE("conditional_update"),
    LOCK_TIMEOUT("lock_timeout");

    final String tag;

    Cause(String tag) {
      this.tag = tag;
    }
  }

  @FunctionalInterface
  interface Sleeper {
    void sleep(Duration delay) throws InterruptedException;
  }

  private final StockProperties.Retry settings;
  private final Sleeper sleeper;
  private final DoubleSupplier random;
  private final Map<Cause, Counter> retries = new EnumMap<>(Cause.class);
  private final Map<Cause, Counter> busy = new EnumMap<>(Cause.class);

  @Autowired
  public StockRetry(StockProperties properties, MeterRegistry meters) {
    this(
        properties.getRetry(),
        meters,
        delay -> Thread.sleep(delay.toMillis()),
        () -> ThreadLocalRandom.current().nextDouble());
  }

  StockRetry(
      StockProperties.Retry settings,
      MeterRegistry meters,
      Sleeper sleeper,
      DoubleSupplier random) {
    this.settings = settings;
    this.sleeper = sleeper;
    this.random = random;
    for (Cause cause : Cause.values()) {
      retries.put(cause, Counter.builder(RETRY_METRIC).tag("cause", cause.tag).register(meters));
      busy.put(cause, Counter.builder(BUSY_METRIC).tag("cause", cause.tag).register(meters));
    }
  }

  public <T> T execute(String operation, Supplier<T> work) {
    // Step 1: Retrying inside a caller's transaction is not safe. Fail before doing anything.
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "stock retry must run outside a transaction (" + operation + ")");
    }
    int attempt = 0;
    while (true) {
      try {
        return work.get();
      } catch (RuntimeException ex) {
        // Step 2: Only transient lock outcomes qualify. Everything else goes straight out.
        Cause cause = classify(ex);
        if (cause == null) {
          throw ex;
        }
        if (cause == Cause.LOCK_TIMEOUT) {
          busy.get(cause).increment();
          throw new StockBusyException(cause.tag, ex);
        }
        if (attempt >= settings.getMaxRetries()) {
          busy.get(cause).increment();
          log.warn("stock {} gave up after {} retries on {}", operation, attempt, cause.tag);
          throw new StockBusyException(cause.tag + " retries exhausted", ex);
        }
        // Step 3: Count, back off, and run the whole transaction again.
        attempt++;
        retries.get(cause).increment();
        Duration delay = backoff(attempt);
        log.info(
            "stock {} retry {} after {} in {} ms", operation, attempt, cause.tag, delay.toMillis());
        try {
          sleeper.sleep(delay);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw new StockBusyException("interrupted", ex);
        }
      }
    }
  }

  /**
   * Nominal {@code initial * multiplier^(n-1)}, capped, then jittered into [nominal/2, nominal].
   */
  Duration backoff(int retry) {
    long nominal = nominalBackoffMillis(retry);
    double jitter = Math.min(Math.max(random.getAsDouble(), 0), 1);
    long millis = Math.round(nominal / 2.0 + jitter * nominal / 2.0);
    return Duration.ofMillis(Math.max(1, millis));
  }

  long nominalBackoffMillis(int retry) {
    long cap = settings.getMaxBackoff().toMillis();
    long nominal = settings.getInitialBackoff().toMillis();
    for (int i = 1; i < retry && nominal < cap; i++) {
      nominal = nominal * settings.getMultiplier();
    }
    return Math.min(nominal, cap);
  }

  public static Cause classify(Throwable ex) {
    for (Throwable current = ex; current != null; current = current.getCause()) {
      if (current instanceof StockConflictException) {
        return Cause.CONDITIONAL_UPDATE;
      }
      if (current instanceof SQLException sql && sql.getSQLState() != null) {
        switch (sql.getSQLState()) {
          case "40P01":
            return Cause.DEADLOCK;
          case "40001":
            return Cause.SERIALIZATION;
          case "55P03":
            return Cause.LOCK_TIMEOUT;
          default:
            break;
        }
      }
      if (current.getCause() == current) {
        return null;
      }
    }
    return null;
  }
}
