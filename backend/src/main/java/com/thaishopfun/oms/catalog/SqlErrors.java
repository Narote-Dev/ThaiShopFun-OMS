package com.thaishopfun.oms.catalog;

import java.sql.SQLException;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;

/**
 * Maps Postgres errors by SQLSTATE and constraint name. Message text is never parsed: V4 raises its
 * bundle errors as 23514 with a stable constraint name for exactly this reason.
 */
public final class SqlErrors {

  public static final String UNIQUE_VIOLATION = "23505";
  public static final String FOREIGN_KEY_VIOLATION = "23503";
  public static final String CHECK_VIOLATION = "23514";
  public static final String DEADLOCK = "40P01";
  public static final String SERIALIZATION_FAILURE = "40001";

  private SqlErrors() {}

  /** SQLSTATE and constraint of the first SQL exception in the cause chain, or null. */
  public record Failure(String sqlState, String constraint) {

    public boolean is(String state, String name) {
      return state.equals(sqlState) && name.equals(constraint);
    }

    public boolean retryable() {
      return DEADLOCK.equals(sqlState) || SERIALIZATION_FAILURE.equals(sqlState);
    }
  }

  public static Failure failure(Throwable error) {
    for (Throwable cause = error; cause != null; cause = cause.getCause()) {
      if (cause instanceof PSQLException psql) {
        ServerErrorMessage server = psql.getServerErrorMessage();
        return new Failure(psql.getSQLState(), server == null ? null : server.getConstraint());
      }
      if (cause instanceof SQLException sql && sql.getSQLState() != null) {
        return new Failure(sql.getSQLState(), null);
      }
      if (cause.getCause() == cause) {
        break;
      }
    }
    return null;
  }

  /**
   * Translates a database failure to an API error.
   *
   * @param inUseCode code for a RESTRICT foreign key hit (for example {@code SKU_IN_USE}), or null
   *     when the statement is not a delete
   * @return the API error, or null when the failure is not a known user error (the caller rethrows)
   */
  public static CatalogApiException translate(Throwable error, String inUseCode) {
    Failure failure = failure(error);
    if (failure == null || failure.sqlState() == null) {
      return null;
    }
    String constraint = failure.constraint() == null ? "" : failure.constraint();
    switch (failure.sqlState()) {
      case UNIQUE_VIOLATION -> {
        return switch (constraint) {
          case "sku_tenant_sku_code_key" ->
              CatalogApiException.conflict("SKU_CODE_EXISTS", "sku_code already exists");
          case "warehouse_tenant_code_key" ->
              CatalogApiException.conflict(
                  "WAREHOUSE_CODE_EXISTS", "Warehouse code already exists");
          case "warehouse_one_default_per_tenant_idx" ->
              CatalogApiException.conflict(
                  "CONFLICT", "The default warehouse changed concurrently");
          default -> CatalogApiException.conflict("CONFLICT", "Duplicate value");
        };
      }
      case FOREIGN_KEY_VIOLATION -> {
        if (inUseCode != null) {
          return CatalogApiException.conflict(inUseCode, "Still referenced by other records");
        }
        return CatalogApiException.invalid("A referenced record does not exist");
      }
      case CHECK_VIOLATION -> {
        return switch (constraint) {
          case "sku_bundle_nesting" ->
              new CatalogApiException(
                  422, "NESTED_BUNDLE", "A bundle cannot contain another bundle");
          case "sku_bundle_parent" ->
              new CatalogApiException(
                  422, "BUNDLE_REQUIRED", "Components are only allowed on a bundle SKU");
          case "sku_bundle_stock" ->
              new CatalogApiException(
                  422, "BUNDLE_NOT_STOCKABLE", "A SKU with stock rows cannot be a bundle");
          // A snapshot isolation level reached the is_bundle trigger. That is our bug.
          case "sku_bundle_isolation" -> null;
          default -> CatalogApiException.invalid("A value is out of range");
        };
      }
      default -> {
        return null;
      }
    }
  }
}
