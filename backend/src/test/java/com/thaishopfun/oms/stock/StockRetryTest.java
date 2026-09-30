package com.thaishopfun.oms.stock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Unit tests for the whole-transaction retry wrapper. No database. */
class StockRetryTest {

  private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
  private final List<Duration> sleeps = new ArrayList<>();

  @AfterEach
  void clearTransactionFlag() {
    TransactionSynchronizationManager.setActualTransactionActive(false);
  }

  private StockRetry retry(double jitter) {
    return new StockRetry(new StockProperties().getRetry(), meters, sleeps::add, () -> jitter);
  }

  private static RuntimeException sqlState(String state) {
    // Step 1: Same shape Spring gives us: a DataAccessException wrapping the driver exception.
    return new CannotAcquireLockException("injected", new SQLException("injected", state));
  }

  @Test
  void deadlockIsRetriedAtMostThreeTimesWithGrowingBackoff() {
    AtomicInteger calls = new AtomicInteger();
    StockRetry retry = retry(1.0);

    // Step 1: Always 40P01. One call plus three retries, then StockBusyException.
    assertThatThrownBy(
            () ->
                retry.execute(
                    "test",
                    () -> {
                      calls.incrementAndGet();
                      throw sqlState("40P01");
                    }))
        .isInstanceOf(StockBusyException.class)
        .hasMessageContaining("deadlock retries exhausted");
    assertThat(calls).hasValue(4);

    // Step 2: Nominal 10, 40, 160 ms. Jitter 1.0 is the top of [nominal/2, nominal].
    assertThat(sleeps)
        .containsExactly(Duration.ofMillis(10), Duration.ofMillis(40), Duration.ofMillis(160));
    assertThat(meters.find(StockRetry.RETRY_METRIC).tag("cause", "deadlock").counter().count())
        .isEqualTo(3);
    assertThat(meters.find(StockRetry.BUSY_METRIC).tag("cause", "deadlock").counter().count())
        .isEqualTo(1);
  }

  @Test
  void jitterStaysWithinHalfToFullNominalAndIsCapped() {
    StockRetry low = retry(0.0);
    StockRetry high = retry(1.0);
    for (int attempt = 1; attempt <= 6; attempt++) {
      long nominal = Math.min(10L * (long) Math.pow(4, attempt - 1), 160);
      assertThat(low.nominalBackoffMillis(attempt)).isEqualTo(nominal);
      assertThat(low.backoff(attempt).toMillis()).isEqualTo(Math.round(nominal / 2.0));
      assertThat(high.backoff(attempt).toMillis()).isEqualTo(nominal);
    }
  }

  @Test
  void serializationFailureRecoversOnRetry() {
    AtomicInteger calls = new AtomicInteger();
    String result =
        retry(0.5)
            .execute(
                "test",
                () -> {
                  if (calls.incrementAndGet() < 3) {
                    throw sqlState("40001");
                  }
                  return "ok";
                });
    assertThat(result).isEqualTo("ok");
    assertThat(calls).hasValue(3);
    assertThat(sleeps).hasSize(2);
    assertThat(meters.find(StockRetry.RETRY_METRIC).tag("cause", "serialization").counter().count())
        .isEqualTo(2);
  }

  @Test
  void conditionalMissIsRetried() {
    AtomicInteger calls = new AtomicInteger();
    retry(0.5)
        .execute(
            "test",
            () -> {
              if (calls.incrementAndGet() == 1) {
                throw new StockConflictException("missed");
              }
              return null;
            });
    assertThat(calls).hasValue(2);
  }

  @Test
  void businessErrorsAndConstraintViolationsAreNotRetried() {
    StockRetry retry = retry(0.5);
    List<RuntimeException> failures =
        List.of(
            new StockOperationException(StockError.RESERVATION_NOT_ACTIVE, "x"),
            new IdempotencyConflictException("stock.reserve"),
            new DataIntegrityViolationException("dup", new SQLException("dup", "23505")),
            new IllegalStateException("boom"));
    for (RuntimeException failure : failures) {
      AtomicInteger calls = new AtomicInteger();
      assertThatThrownBy(
              () ->
                  retry.execute(
                      "test",
                      () -> {
                        calls.incrementAndGet();
                        throw failure;
                      }))
          .isSameAs(failure);
      assertThat(calls).as(failure.getClass().getSimpleName()).hasValue(1);
    }
    assertThat(sleeps).isEmpty();
  }

  @Test
  void lockTimeoutIsBusyWithoutRetry() {
    AtomicInteger calls = new AtomicInteger();
    assertThatThrownBy(
            () ->
                retry(0.5)
                    .execute(
                        "test",
                        () -> {
                          calls.incrementAndGet();
                          throw sqlState("55P03");
                        }))
        .isInstanceOf(StockBusyException.class)
        .extracting(ex -> ((StockBusyException) ex).reason())
        .isEqualTo("lock_timeout");
    assertThat(calls).hasValue(1);
  }

  @Test
  void refusesToRunInsideAnOpenTransaction() {
    TransactionSynchronizationManager.setActualTransactionActive(true);
    AtomicInteger calls = new AtomicInteger();
    assertThatThrownBy(() -> retry(0.5).execute("test", calls::incrementAndGet))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("outside a transaction");
    assertThat(calls).hasValue(0);
  }
}
