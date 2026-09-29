package com.thaishopfun.oms.tenant;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionSystemException;

/**
 * Pins {@code app.tenant_id} to the transaction that {@code doBegin} opens.
 *
 * <p>Spring Framework 7 keeps {@code DataSourceTransactionObject} private, so this subclass does
 * not cast it. {@code doBegin} calls {@link #prepareTransactionalConnection} on the connection it
 * just took out of auto-commit, and a failure there is still released by {@code doBegin}. {@code
 * set_config(..., true)} is local: Postgres clears it on commit and on rollback, including the
 * empty string it leaves behind, which the V1 policy treats as no tenant.
 */
public class TenantAwareDataSourceTransactionManager extends DataSourceTransactionManager {

  public TenantAwareDataSourceTransactionManager(javax.sql.DataSource dataSource) {
    super(dataSource);
  }

  @Override
  protected void prepareTransactionalConnection(
      Connection connection, TransactionDefinition definition) throws SQLException {
    // Step 1: Let the superclass apply a read-only hint when that flag is on.
    super.prepareTransactionalConnection(connection, definition);
    // Step 2: Bind the ThreadLocal tenant for this transaction only. Empty means fail closed.
    bindTenant(connection);
  }

  private static void bindTenant(Connection connection) throws SQLException {
    UUID tenantId = TenantContext.tenantId();
    String value = tenantId == null ? "" : tenantId.toString();
    try (PreparedStatement statement =
        connection.prepareStatement("SELECT set_config('app.tenant_id', ?, true)")) {
      statement.setString(1, value);
      try (ResultSet rows = statement.executeQuery()) {
        if (!rows.next()) {
          throw new TransactionSystemException("set_config returned no row");
        }
      }
    }
  }
}
