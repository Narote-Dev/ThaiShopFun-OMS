package com.thaishopfun.oms.inbox;

import java.util.Set;

/** Which inbox event types use per-{@code event_type} aggregate_version history. */
public final class InboxAggregateVersionPolicy {

  private static final Set<String> ORDER_DELTA_BY_EVENT_TYPE =
      Set.of("order.created", "order.paid", "order.cancelled", "order.updated");

  private InboxAggregateVersionPolicy() {}

  public static boolean versionByEventType(String eventType) {
    return ORDER_DELTA_BY_EVENT_TYPE.contains(eventType);
  }

  /** Quoted literals for {@code event_type IN (...)} in SQL. */
  public static String orderDeltaTypeLiterals() {
    StringBuilder literals = new StringBuilder();
    for (String type : ORDER_DELTA_BY_EVENT_TYPE) {
      if (!type.matches("[a-z0-9.]+")) {
        throw new IllegalStateException("event type is not a safe SQL literal");
      }
      if (literals.length() > 0) {
        literals.append(", ");
      }
      literals.append('\'').append(type).append('\'');
    }
    return literals.toString();
  }
}
