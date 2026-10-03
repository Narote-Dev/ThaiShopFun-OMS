package com.thaishopfun.oms.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Set;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;

/** Pure jqwik checks (no Spring). */
class OrderStateMachineJqwikTest {

  @Property(tries = 100)
  void expectedMatrixMatchesCodeTable(
      @ForAll("dimension") String dimension, @ForAll("from") String from, @ForAll("to") String to) {
    if (!OrderStateMachine.allowedValues(dimension).contains(from)
        || !OrderStateMachine.allowedValues(dimension).contains(to)) {
      return;
    }
    boolean expected = OrderStateMachineExpectedTransitions.isLegalEdge(dimension, from, to);
    Set<String> codeTargets =
        OrderStateMachine.transitionTable()
            .getOrDefault(dimension, Map.of())
            .getOrDefault(from, Set.of());
    boolean code = from.equals(to) || codeTargets.contains(to);
    assertThat(code).isEqualTo(expected);
  }

  @net.jqwik.api.Provide
  net.jqwik.api.Arbitrary<String> dimension() {
    return net.jqwik.api.Arbitraries.of("ORDER", "PAYMENT", "FULFILLMENT", "HOLD");
  }

  @net.jqwik.api.Provide
  net.jqwik.api.Arbitrary<String> from() {
    return net.jqwik.api.Arbitraries.of(
        "ACTIVE", "PENDING", "UNFULFILLED", "NONE", "PAID", "CANCELLED", "READY_TO_PICK");
  }

  @net.jqwik.api.Provide
  net.jqwik.api.Arbitrary<String> to() {
    return from();
  }
}
