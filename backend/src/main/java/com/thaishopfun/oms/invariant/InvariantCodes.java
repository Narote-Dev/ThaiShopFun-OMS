package com.thaishopfun.oms.invariant;

/** Stable violation codes for metrics and deliberate-break tests. */
public final class InvariantCodes {

  public static final String STOCK_RESERVED_BOUNDS = "stock.reserved_bounds";
  public static final String STOCK_LEDGER_MISMATCH = "stock.ledger_mismatch";
  public static final String STOCK_ACTIVE_RESERVATION_MISMATCH =
      "stock.active_reservation_mismatch";
  public static final String STOCK_RESERVATION_OWNER_SPLIT = "stock.reservation_owner_split";
  public static final String SCHEMA_FORCE_RLS = "schema.force_rls";
  public static final String SCHEMA_OMS_APP_ROLE = "schema.oms_app_role";
  public static final String ORDER_RESERVATION_ORPHAN = "order.reservation_orphan";
  public static final String ORDER_CANCELLED_ACTIVE_RESERVATION =
      "order.cancelled_active_reservation";
  public static final String ORDER_TERMINAL_ACTIVE_RESERVATION =
      "order.terminal_active_reservation";
  public static final String ORDER_READY_TO_PICK_HOLD = "order.ready_to_pick_hold";
  public static final String ORDER_STATUS_HISTORY_MISSING = "order.status_history_missing";
  public static final String ORDER_READY_TO_PICK_COVERAGE = "order.ready_to_pick_coverage";

  /** Nightly job could not complete {@link InvariantChecker#checkTenant} for one tenant. */
  public static final String CHECK_FAILED = "check_failed";

  private InvariantCodes() {}
}
