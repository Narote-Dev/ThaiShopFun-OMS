package com.thaishopfun.oms.checkout;

import java.sql.SQLException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.transaction.TransactionTimedOutException;

final class CheckoutTimeouts {

  private static final String QUERY_CANCELED = "57014";

  private CheckoutTimeouts() {}

  static boolean isStockBusyTimeout(Throwable error) {
    for (Throwable cause = error; cause != null; cause = cause.getCause()) {
      if (cause instanceof TransactionTimedOutException || cause instanceof QueryTimeoutException) {
        return true;
      }
      if (cause instanceof SQLException sql && QUERY_CANCELED.equals(sql.getSQLState())) {
        return true;
      }
      if (cause.getCause() == cause) {
        break;
      }
    }
    return false;
  }
}
