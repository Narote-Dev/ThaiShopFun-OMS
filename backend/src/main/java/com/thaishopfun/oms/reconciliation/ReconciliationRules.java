package com.thaishopfun.oms.reconciliation;

import java.util.List;
import java.util.Set;

/** Stable rule ids for reconciliation_issue.rule (plan §1.5). */
public final class ReconciliationRules {

  public static final String SHIP_BY_LATE = "SHIP_BY_LATE";
  public static final String SHIPPED_UNPAID = "SHIPPED_UNPAID";
  public static final String PAID_AFTER_CANCEL = "PAID_AFTER_CANCEL";
  public static final String COD_COLLECTION_OVERDUE = "COD_COLLECTION_OVERDUE";
  public static final String REFUND_MISSING_AFTER_RETURN = "REFUND_MISSING_AFTER_RETURN";
  public static final String PAYMENT_AMOUNT_MISMATCH = "PAYMENT_AMOUNT_MISMATCH";
  public static final String STOCK_DRIFT = "STOCK_DRIFT";
  public static final String INVARIANT_VIOLATION = "INVARIANT_VIOLATION";

  public static final Set<String> ALL =
      Set.of(
          SHIP_BY_LATE,
          SHIPPED_UNPAID,
          PAID_AFTER_CANCEL,
          COD_COLLECTION_OVERDUE,
          REFUND_MISSING_AFTER_RETURN,
          PAYMENT_AMOUNT_MISMATCH,
          STOCK_DRIFT,
          INVARIANT_VIOLATION);

  public static final List<String> ORDER_RULES =
      List.of(
          SHIP_BY_LATE,
          SHIPPED_UNPAID,
          PAID_AFTER_CANCEL,
          COD_COLLECTION_OVERDUE,
          REFUND_MISSING_AFTER_RETURN,
          PAYMENT_AMOUNT_MISMATCH);

  private ReconciliationRules() {}
}
