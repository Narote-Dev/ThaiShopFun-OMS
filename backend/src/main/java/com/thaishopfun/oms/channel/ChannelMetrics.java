package com.thaishopfun.oms.channel;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

@Component
public class ChannelMetrics {

  private static final String CALLS = "oms.channel.calls";
  private static final String DURATION = "oms.channel.call.duration";
  private static final String RETRIES = "oms.channel.retries";
  private static final String CIRCUIT_STATE = "oms.channel.circuit.state";
  private static final String BULKHEAD_WAITING = "oms.channel.bulkhead.waiting";

  private final MeterRegistry registry;
  private final Map<Channel, Bulkhead> bulkheads = new EnumMap<>(Channel.class);
  private final Map<Channel, AtomicInteger> bulkheadWaiting = new EnumMap<>(Channel.class);
  private final Map<Channel, Set<CircuitBreaker>> circuitBreakers = new EnumMap<>(Channel.class);
  private final Map<Channel, Boolean> gaugeRegistered = new EnumMap<>(Channel.class);

  public ChannelMetrics(MeterRegistry registry) {
    this.registry = registry;
    for (Channel channel : Channel.values()) {
      bulkheadWaiting.put(channel, new AtomicInteger());
      circuitBreakers.put(channel, ConcurrentHashMap.newKeySet());
    }
  }

  public void registerBulkhead(Channel channel, Bulkhead bulkhead) {
    bulkheads.putIfAbsent(channel, bulkhead);
    ensureGauges(channel);
  }

  public void registerCircuitBreaker(Channel channel, CircuitBreaker circuitBreaker) {
    circuitBreakers.get(channel).add(circuitBreaker);
    ensureGauges(channel);
  }

  public void enterBulkheadWait(Channel channel) {
    bulkheadWaiting.get(channel).incrementAndGet();
  }

  public void leaveBulkheadWait(Channel channel) {
    bulkheadWaiting.get(channel).decrementAndGet();
  }

  private void ensureGauges(Channel channel) {
    if (Boolean.TRUE.equals(gaugeRegistered.get(channel))) {
      return;
    }
    if (registry.find(BULKHEAD_WAITING).tag("channel", channel.name()).gauge() != null
        && registry.find(CIRCUIT_STATE).tag("channel", channel.name()).gauge() != null) {
      gaugeRegistered.put(channel, true);
      return;
    }
    Gauge.builder(BULKHEAD_WAITING, () -> bulkheadWaiting.get(channel).get())
        .tag("channel", channel.name())
        .register(registry);
    Gauge.builder(CIRCUIT_STATE, () -> worstCircuitState(circuitBreakers.get(channel)))
        .tag("channel", channel.name())
        .register(registry);
    gaugeRegistered.put(channel, true);
  }

  private static double worstCircuitState(Set<CircuitBreaker> breakers) {
    if (breakers == null || breakers.isEmpty()) {
      return stateCode(CircuitBreaker.State.CLOSED);
    }
    int worst = 0;
    for (CircuitBreaker breaker : breakers) {
      worst = Math.max(worst, (int) stateCode(breaker.getState()));
    }
    return worst;
  }

  private static double stateCode(CircuitBreaker.State state) {
    return switch (state) {
      case CLOSED -> 0;
      case HALF_OPEN -> 1;
      case OPEN -> 2;
      case DISABLED -> 3;
      case FORCED_OPEN -> 4;
      case METRICS_ONLY -> 5;
    };
  }

  public Timer.Sample startTimer() {
    return Timer.start(registry);
  }

  public void recordDuration(Channel channel, String operation, Timer.Sample sample) {
    sample.stop(
        Timer.builder(DURATION)
            .tag("channel", channel.name())
            .tag("operation", operation)
            .register(registry));
  }

  public void recordCall(Channel channel, String operation, String outcome) {
    Counter.builder(CALLS)
        .tag("channel", channel.name())
        .tag("operation", operation)
        .tag("outcome", outcome)
        .register(registry)
        .increment();
  }

  public void recordRetry(Channel channel, String operation, String reason) {
    Counter.builder(RETRIES)
        .tag("channel", channel.name())
        .tag("operation", operation)
        .tag("reason", reason)
        .register(registry)
        .increment();
  }
}
