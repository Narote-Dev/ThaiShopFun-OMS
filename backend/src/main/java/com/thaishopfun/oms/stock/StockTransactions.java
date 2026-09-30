package com.thaishopfun.oms.stock;

import com.thaishopfun.oms.tenant.TenantContext;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
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
 */
@Component
class StockTransactions {

  static final String READ_COMMITTED_REQUIRED = "READ_COMMITTED_REQUIRED";

  private static final int TRANSACTION_TIMEOUT_SECONDS = 10;

  private final JdbcTemplate jdbc;
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

  /** A write. Retried whole on deadlock or serialization failure when the engine owns the tx. */
  <T> T write(String operation, Supplier<T> work) {
    // Step 1: Tenant first. No context means no transaction at all.
    UUID tenantId = TenantContext.requireTenantId();
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      // Step 2: Join the caller. Its isolation and tenant are checked, its settings untouched.
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
