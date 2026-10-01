package com.thaishopfun.oms.channel;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.EnumMap;
import java.util.Map;
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
  private final Map<Channel, CircuitBreaker> circuitSample = new EnumMap<>(Channel.class);

  public ChannelMetrics(MeterRegistry registry) {
    this.registry = registry;
  }

  public void registerBulkhead(Channel channel, Bulkhead bulkhead) {
    if (bulkheads.containsKey(channel)) {
      return;
    }
    bulkheads.put(channel, bulkhead);
    Gauge.builder(BULKHEAD_WAITING, bulkhead, ChannelMetrics::bulkheadInUse)
        .tag("channel", channel.name())
        .register(registry);
  }

  public void registerCircuitBreaker(Channel channel, CircuitBreaker circuitBreaker) {
    circuitSample.putIfAbsent(channel, circuitBreaker);
    CircuitBreaker sample = circuitSample.get(channel);
    if (sample != circuitBreaker) {
      return;
    }
    if (registry.find(CIRCUIT_STATE).tag("channel", channel.name()).gauge() != null) {
      return;
    }
    Gauge.builder(CIRCUIT_STATE, circuitBreaker, cb -> stateCode(cb.getState()))
        .tag("channel", channel.name())
        .register(registry);
  }

  private static double bulkheadInUse(Bulkhead bulkhead) {
    var metrics = bulkhead.getMetrics();
    return metrics.getMaxAllowedConcurrentCalls() - metrics.getAvailableConcurrentCalls();
  }

  private static double stateCode(CircuitBreaker.State state) {
    return switch (state) {
      case CLOSED -> 0;
      case OPEN -> 1;
      case HALF_OPEN -> 2;
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
