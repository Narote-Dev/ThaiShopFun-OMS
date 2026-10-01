package com.thaishopfun.oms.stock;

import com.thaishopfun.oms.tenant.TenantContext;
import java.util.UUID;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs engine work in a READ COMMITTED tenant transaction.
 *
 * <p>With no transaction open, the engine owns one: explicit READ COMMITTED, {@code SET LOCAL
 * lock_timeout}, and the whole transaction wrapped by {@link StockRetry}. Inside a caller's
 * transaction (T12 order intake, T08A posting) the work joins it without retry, because only the
 * caller can re-run its own transaction. Either way the first statement asserts READ COMMITTED and
 * that {@code app.tenant_id} is the current {@link TenantContext} tenant, and fails fast otherwise.
 * The conditional-UPDATE design needs a fresh snapshot per statement.
 *
 * <p>Joined callers: at most one engine write per caller transaction. A second write in the same
 * transaction would lock inventory again after locks it already holds, outside the id order. The
 * engine cannot retry a transaction it does not own, so the caller must retry its whole transaction
 * on {@code 40P01} (deadlock_detected) and {@code 40001} (serialization_failure), and should take
 * no stock row locks before calling the engine.
 *
 * <p>Stock document post and void ({@link StockMovements}) never join: {@link #writeOwned} asserts
 * the caller's isolation (so REPEATABLE READ still fails with {@code READ_COMMITTED_REQUIRED}) and
 * then refuses. Their lock order is the key row, then the {@code stock_document} row {@code FOR
 * UPDATE}, then inventory rows in id order, and nothing else locks {@code stock_document} while
 * holding inventory.
 */
@Component
class StockTransactions {

  static final String READ_COMMITTED_REQUIRED = "READ_COMMITTED_REQUIRED";

  private static final int TRANSACTION_TIMEOUT_SECONDS = 10;

  private final JdbcTemplate jdbc;
  private final DataSource dataSource;
  private final StockRetry retry;
  private final StockProperties properties;
  private final TransactionTemplate writeTx;
  private final TransactionTemplate readTx;

  StockTransactions(
      JdbcTemplate jdbc,
      PlatformTransactionManager transactions,
      StockRetry retry,
      StockProperties properties) {
    this.jdbc = jdbc;
    this.dataSource = jdbc.getDataSource();
    this.retry = retry;
    this.properties = properties;
    this.writeTx = new TransactionTemplate(transactions);
    this.writeTx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    this.writeTx.setTimeout(TRANSACTION_TIMEOUT_SECONDS);
    this.readTx = new TransactionTemplate(transactions);
    this.readTx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    this.readTx.setReadOnly(true);
    this.readTx.setTimeout(TRANSACTION_TIMEOUT_SECONDS);
  }

  /**
   * A write. Retried whole on deadlock or serialization failure when the engine owns the tx. Inside
   * a caller's transaction it runs once, without retry: the caller must retry its whole transaction
   * on {@code 40P01} and {@code 40001}. A second write in the same caller transaction throws {@link
   * IllegalStateException}.
   */
  <T> T write(String operation, Supplier<T> work) {
    // Step 1: Tenant first. No context means no transaction at all.
    UUID tenantId = TenantContext.requireTenantId();
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      // Step 2: Join the caller, once. Its isolation and tenant are checked, settings untouched.
      // Change: one engine write per caller transaction, so the lock order cannot be broken.
      claimJoinedWrite(operation);
      prepare(tenantId, false);
      return work.get();
    }
    // Step 3: Own it. The retry loop sits outside the transaction boundary.
    return retry.execute(
        operation,
        () ->
            writeTx.execute(
                status -> {
                  prepare(tenantId, true);
                  return work.get();
                }));
  }

  // Change: T08A post/void never join a caller transaction.
  /**
   * A write that must own its transaction (stock document post and void). Inside a caller's
   * transaction it checks isolation and tenant first, then throws {@link IllegalStateException}.
   */
  <T> T writeOwned(String operation, Supplier<T> work) {
    // Step 1: Tenant first, as for every write.
    UUID tenantId = TenantContext.requireTenantId();
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      // Step 2: A snapshot-isolation caller gets the usual READ_COMMITTED_REQUIRED. Any other
      // caller is refused: the post locks the document before inventory and retries itself.
      prepare(tenantId, false);
      throw new IllegalStateException(
          "stock " + operation + " owns its transaction; call it outside any transaction");
    }
    // Step 3: Same owned path as write: READ COMMITTED, lock_timeout, whole-transaction retry.
    return write(operation, work);
  }

  /** A read. Takes no row locks, so there is nothing to retry. */
  <T> T read(Supplier<T> work) {
    UUID tenantId = TenantContext.requireTenantId();
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      prepare(tenantId, false);
      return work.get();
    }
    return readTx.execute(
        status -> {
          prepare(tenantId, false);
          return work.get();
        });
  }

  /**
   * Marks the caller's transaction as having used its one engine write. The flag is keyed by the
   * transaction's connection holder, so a REQUIRES_NEW transaction inside it gets its own, and it
   * is unbound when the transaction completes.
   */
  private void claimJoinedWrite(String operation) {
    // Step 1: A joined write needs synchronization to clear the flag at completion.
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      throw new IllegalStateException(
          "stock " + operation + " joined a transaction without synchronization");
    }
    Object holder = TransactionSynchronizationManager.getResource(dataSource);
    JoinedWriteKey key = new JoinedWriteKey(holder == null ? dataSource : holder);
    // Step 2: The second write in the same caller transaction is refused.
    if (TransactionSynchronizationManager.hasResource(key)) {
      throw new IllegalStateException(
          "stock "
              + operation
              + ": only one engine write per caller transaction (already used by "
              + TransactionSynchronizationManager.getResource(key)
              + ")");
    }
    TransactionSynchronizationManager.bindResource(key, operation);
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCompletion(int status) {
            TransactionSynchronizationManager.unbindResourceIfPossible(key);
          }
        });
  }

  /** Identity of the caller transaction's resource holder. Records compare by component. */
  private record JoinedWriteKey(Object holder) {

    @Override
    public boolean equals(Object other) {
      return other instanceof JoinedWriteKey that && that.holder == holder;
    }

    @Override
    public int hashCode() {
      return System.identityHashCode(holder);
    }
  }

  private void prepare(UUID tenantId, boolean owned) {
    // Step 1: One round trip: isolation, bound tenant, and (own transaction only) lock_timeout.
    String sql =
        owned
            ? "SELECT current_setting('transaction_isolation') AS isolation, "
                + "current_setting('app.tenant_id', true) AS tenant, "
                + "set_config('lock_timeout', ?, true) AS lock_timeout"
            : "SELECT current_setting('transaction_isolation') AS isolation, "
                + "current_setting('app.tenant_id', true) AS tenant";
    String[] settings =
        jdbc.query(
            sql,
            ps -> {
              if (owned) {
                ps.setString(1, properties.getLockTimeout().toMillis() + "ms");
              }
            },
            rs -> {
              rs.next();
              return new String[] {rs.getString("isolation"), rs.getString("tenant")};
            });
    // Step 2: Fail fast. REPEATABLE READ or SERIALIZABLE would hide concurrent commits.
    if (settings == null || !"read committed".equals(settings[0])) {
      throw new IllegalStateException(
          READ_COMMITTED_REQUIRED
              + ": the stock engine runs at READ COMMITTED only (current: "
              + (settings == null ? "unknown" : settings[0])
              + ")");
    }
    // Step 3: The transaction must be bound to the same tenant the caller is acting for.
    if (!tenantId.toString().equals(settings[1])) {
      throw new IllegalStateException("transaction tenant does not match TenantContext");
    }
  }
}
