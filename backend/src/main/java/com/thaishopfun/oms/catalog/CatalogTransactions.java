package com.thaishopfun.oms.catalog;

import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Catalog transactions. Writes run at READ COMMITTED explicitly: the V4 {@code is_bundle} trigger
 * refuses a flip at any snapshot level. A deadlock (40P01) or serialization failure retries the
 * whole transaction a bounded number of times. The tenant is bound by the tenant-aware transaction
 * manager, as for every other transaction.
 */
@Component
public class CatalogTransactions {

  static final int MAX_ATTEMPTS = 3;

  private static final Logger log = LoggerFactory.getLogger(CatalogTransactions.class);

  private final TransactionTemplate write;
  private final TransactionTemplate read;

  public CatalogTransactions(PlatformTransactionManager transactions) {
    this.write = new TransactionTemplate(transactions);
    write.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    write.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
    this.read = new TransactionTemplate(transactions);
    read.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    read.setReadOnly(true);
  }

  public <T> T read(Supplier<T> work) {
    return run(read, null, failure -> false, work);
  }

  public <T> T write(String inUseCode, Supplier<T> work) {
    return run(write, inUseCode, failure -> false, work);
  }

  /** Like {@link #write} and also retries when {@code extraRetry} matches the failure. */
  public <T> T write(String inUseCode, Predicate<SqlErrors.Failure> extraRetry, Supplier<T> work) {
    return run(write, inUseCode, extraRetry, work);
  }

  private <T> T run(
      TransactionTemplate template,
      String inUseCode,
      Predicate<SqlErrors.Failure> extraRetry,
      Supplier<T> work) {
    // Step 1: Inside an outer transaction a retry would replay only part of it. Run once.
    int attempts = TransactionSynchronizationManager.isActualTransactionActive() ? 1 : MAX_ATTEMPTS;
    for (int attempt = 1; ; attempt++) {
      try {
        return template.execute(status -> work.get());
      } catch (CatalogApiException ex) {
        throw ex;
      } catch (RuntimeException ex) {
        SqlErrors.Failure failure = SqlErrors.failure(ex);
        // Step 2: Deadlock or serialization failure: back off with jitter, then run it all again.
        if (failure != null
            && (failure.retryable() || extraRetry.test(failure))
            && attempt < attempts) {
          log.info("catalog transaction retry sqlstate={} attempt={}", failure.sqlState(), attempt);
          backoff(attempt);
          continue;
        }
        // Step 3: Known constraint and trigger errors become API errors. Anything else is a 500.
        CatalogApiException mapped = SqlErrors.translate(ex, inUseCode);
        if (mapped != null) {
          throw mapped;
        }
        if (failure != null && "sku_bundle_isolation".equals(failure.constraint())) {
          log.error("is_bundle changed outside READ COMMITTED; this is a bug");
        }
        throw ex;
      }
    }
  }

  private static void backoff(int attempt) {
    long millis = ThreadLocalRandom.current().nextLong(10, 40) * attempt;
    try {
      Thread.sleep(millis);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted during retry backoff", interrupted);
    }
  }
}
