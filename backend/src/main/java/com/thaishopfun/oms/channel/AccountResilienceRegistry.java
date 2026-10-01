package com.thaishopfun.oms.channel;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/** Per-account rate limiter and circuit breaker; per-{@link Channel} bulkhead. */
@Component
public class AccountResilienceRegistry {

  private final ChannelProperties properties;
  private final ChannelMetrics metrics;
  private final Map<UUID, RateLimiter> rateLimiters = new ConcurrentHashMap<>();
  private final Map<UUID, CircuitBreaker> circuitBreakers = new ConcurrentHashMap<>();
  private final Map<Channel, Bulkhead> bulkheads = new EnumMap<>(Channel.class);
  private final RateLimiterRegistry rateLimiterRegistry;
  private final CircuitBreakerRegistry circuitBreakerRegistry;
  private final BulkheadRegistry bulkheadRegistry;

  public AccountResilienceRegistry(ChannelProperties properties, ChannelMetrics metrics) {
    this.properties = properties;
    this.metrics = metrics;
    this.rateLimiterRegistry = RateLimiterRegistry.ofDefaults();
    this.circuitBreakerRegistry = CircuitBreakerRegistry.ofDefaults();
    this.bulkheadRegistry = BulkheadRegistry.ofDefaults();
    for (Channel channel : Channel.values()) {
      ensureBulkhead(channel);
    }
  }

  public RateLimiter rateLimiter(Channel channel, UUID channelAccountId) {
    return rateLimiters.computeIfAbsent(channelAccountId, id -> createRateLimiter(channel, id));
  }

  public CircuitBreaker circuitBreaker(Channel channel, UUID channelAccountId) {
    return circuitBreakers.computeIfAbsent(
        channelAccountId, id -> createCircuitBreaker(channel, id));
  }

  public Bulkhead bulkhead(Channel channel) {
    return ensureBulkhead(channel);
  }

  private Bulkhead ensureBulkhead(Channel channel) {
    return bulkheads.computeIfAbsent(
        channel,
        ch -> {
          ChannelProperties.TsfChannelSettings settings = properties.settingsFor(ch);
          BulkheadConfig config =
              BulkheadConfig.custom()
                  .maxConcurrentCalls(settings.getBulkheadMaxConcurrent())
                  .maxWaitDuration(settings.getBulkheadMaxWait())
                  .build();
          Bulkhead bulkhead = bulkheadRegistry.bulkhead(ch.name() + "-bulkhead", config);
          metrics.registerBulkhead(ch, bulkhead);
          return bulkhead;
        });
  }

  private RateLimiter createRateLimiter(Channel channel, UUID channelAccountId) {
    ChannelProperties.TsfChannelSettings settings = properties.settingsFor(channel);
    RateLimiterConfig config =
        RateLimiterConfig.custom()
            .limitForPeriod(settings.getRateLimitPerSecond())
            .limitRefreshPeriod(Duration.ofSeconds(1))
            .timeoutDuration(settings.getRateLimitWait())
            .build();
    return rateLimiterRegistry.rateLimiter(channel.name() + ":" + channelAccountId, config);
  }

  private CircuitBreaker createCircuitBreaker(Channel channel, UUID channelAccountId) {
    ChannelProperties.TsfChannelSettings settings = properties.settingsFor(channel);
    CircuitBreakerConfig config =
        CircuitBreakerConfig.custom()
            .failureRateThreshold(settings.getCircuitFailureRateThreshold())
            .slidingWindowSize(settings.getCircuitSlidingWindowSize())
            .minimumNumberOfCalls(settings.getCircuitMinimumNumberOfCalls())
            .waitDurationInOpenState(settings.getCircuitWaitInOpenState())
            .permittedNumberOfCallsInHalfOpenState(settings.getCircuitPermittedCallsInHalfOpen())
            .recordExceptions(
                com.thaishopfun.oms.channel.exception.ChannelUnavailableException.class)
            .build();
    CircuitBreaker breaker =
        circuitBreakerRegistry.circuitBreaker(channel.name() + ":" + channelAccountId, config);
    metrics.registerCircuitBreaker(channel, breaker);
    return breaker;
  }
}
