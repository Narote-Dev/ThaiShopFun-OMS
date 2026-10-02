package com.thaishopfun.oms.channel;

import com.thaishopfun.oms.channel.api.CancelRequest;
import com.thaishopfun.oms.channel.api.CancelResponse;
import com.thaishopfun.oms.channel.api.LabelContent;
import com.thaishopfun.oms.channel.api.ListingPage;
import com.thaishopfun.oms.channel.api.OrderDetail;
import com.thaishopfun.oms.channel.api.OrderPage;
import com.thaishopfun.oms.channel.api.PaymentStatus;
import com.thaishopfun.oms.channel.api.Shipment;
import com.thaishopfun.oms.channel.api.ShipmentRequest;
import com.thaishopfun.oms.channel.exception.ChannelClientException;
import com.thaishopfun.oms.channel.exception.ChannelIdempotencyConflictException;
import com.thaishopfun.oms.channel.exception.ChannelRateLimitedException;
import com.thaishopfun.oms.channel.exception.ChannelServerErrorException;
import com.thaishopfun.oms.channel.exception.ChannelUnavailableException;
import com.thaishopfun.oms.channel.exception.UnsupportedCapabilityException;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.internal.AtomicRateLimiter;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BooleanSupplier;

/**
 * Decorator order: capability check → retry loop. Each attempt acquires the bulkhead (bounded
 * wait), reserves rate-limiter capacity, runs circuit breaker → {@link ChannelDeadlineCall}, then
 * releases the bulkhead before any retry sleep. The call {@link
 * ChannelProperties.TsfChannelSettings#getCallTimeBudget() time budget} caps total wall time; each
 * HTTP/token operation uses {@code min(http-timeout, remaining)}.
 */
public abstract class BaseChannelAdapter implements ChannelAdapter {

  private static final Duration BULKHEAD_POLL_INTERVAL = Duration.ofMillis(5);

  private final AccountResilienceRegistry resilience;
  private final ChannelProperties properties;
  private final ChannelMetrics metrics;
  private final Sleeper sleeper;
  private final Clock clock;

  protected BaseChannelAdapter(
      AccountResilienceRegistry resilience,
      ChannelProperties properties,
      ChannelMetrics metrics,
      Sleeper sleeper,
      Clock clock) {
    this.resilience = resilience;
    this.properties = properties;
    this.metrics = metrics;
    this.sleeper = sleeper;
    this.clock = clock;
  }

  @Override
  public final OrderPage listOrders(
      ChannelAccountRef account, Instant updatedSince, String cursor, int limit) {
    requireCapability("listOrders", capabilities()::supportsOrderPull);
    return invoke(
        account,
        "listOrders",
        deadline -> doListOrders(account, updatedSince, cursor, limit, deadline));
  }

  @Override
  public final OrderDetail getOrder(ChannelAccountRef account, String externalOrderId) {
    requireCapability("getOrder", capabilities()::supportsOrderPull);
    return invoke(account, "getOrder", deadline -> doGetOrder(account, externalOrderId, deadline));
  }

  @Override
  public final PaymentStatus getPaymentStatus(ChannelAccountRef account, String externalOrderId) {
    requireCapability("getPaymentStatus", capabilities()::supportsOrderPull);
    return invoke(
        account,
        "getPaymentStatus",
        deadline -> doGetPaymentStatus(account, externalOrderId, deadline));
  }

  @Override
  public final ListingPage listListings(ChannelAccountRef account, String cursor) {
    requireCapability("listListings", capabilities()::supportsStockPush);
    return invoke(account, "listListings", deadline -> doListListings(account, cursor, deadline));
  }

  @Override
  public final Shipment createShipment(
      ChannelAccountRef account,
      String externalOrderId,
      String idempotencyKey,
      ShipmentRequest request) {
    requireCapability("createShipment", capabilities()::supportsLabel);
    requireIdempotencyKey(idempotencyKey);
    if (request.partial() && !capabilities().supportsPartialShipment()) {
      throw new UnsupportedCapabilityException(channel(), "createShipment(partial)");
    }
    return invoke(
        account,
        "createShipment",
        deadline -> doCreateShipment(account, externalOrderId, idempotencyKey, request, deadline));
  }

  @Override
  public final LabelContent getLabel(ChannelAccountRef account, String shipmentId) {
    requireCapability("getLabel", capabilities()::supportsLabel);
    return invoke(account, "getLabel", deadline -> doGetLabel(account, shipmentId, deadline));
  }

  @Override
  public final CancelResponse requestCancel(
      ChannelAccountRef account,
      String externalOrderId,
      String idempotencyKey,
      CancelRequest request) {
    requireCapability("requestCancel", capabilities()::supportsCancelRequest);
    requireIdempotencyKey(idempotencyKey);
    return invoke(
        account,
        "requestCancel",
        deadline -> doRequestCancel(account, externalOrderId, idempotencyKey, request, deadline));
  }

  protected abstract OrderPage doListOrders(
      ChannelAccountRef account, Instant updatedSince, String cursor, int limit, Instant deadline);

  protected abstract OrderDetail doGetOrder(
      ChannelAccountRef account, String externalOrderId, Instant deadline);

  protected abstract PaymentStatus doGetPaymentStatus(
      ChannelAccountRef account, String externalOrderId, Instant deadline);

  protected abstract ListingPage doListListings(
      ChannelAccountRef account, String cursor, Instant deadline);

  protected abstract Shipment doCreateShipment(
      ChannelAccountRef account,
      String externalOrderId,
      String idempotencyKey,
      ShipmentRequest request,
      Instant deadline);

  protected abstract LabelContent doGetLabel(
      ChannelAccountRef account, String shipmentId, Instant deadline);

  protected abstract CancelResponse doRequestCancel(
      ChannelAccountRef account,
      String externalOrderId,
      String idempotencyKey,
      CancelRequest request,
      Instant deadline);

  protected ChannelProperties.TsfChannelSettings settings() {
    return properties.settingsFor(channel());
  }

  protected Duration transportTimeout(Instant deadline) {
    return ChannelCallBudget.transportTimeout(clock, settings().getHttpTimeout(), deadline);
  }

  protected Clock clock() {
    return clock;
  }

  private void requireCapability(String operation, BooleanSupplier supported) {
    if (!supported.getAsBoolean()) {
      throw new UnsupportedCapabilityException(channel(), operation);
    }
  }

  private static void requireIdempotencyKey(String idempotencyKey) {
    if (idempotencyKey == null || idempotencyKey.isBlank()) {
      throw new ChannelClientException(
          400, "IDEMPOTENCY_KEY_REQUIRED", "Idempotency-Key is required");
    }
  }

  private <T> T invoke(
      ChannelAccountRef account, String operation, ChannelDeadlineCall<T> httpCall) {
    Timer.Sample sample = metrics.startTimer();
    String outcome = "success";
    Instant deadline = clock.instant().plus(settings().getCallTimeBudget());
    try {
      return invokeWithRetry(account, operation, httpCall, deadline);
    } catch (BulkheadFullException ex) {
      outcome = "bulkhead_rejected";
      throw new ChannelUnavailableException("Channel bulkhead is full for " + channel(), ex);
    } catch (CallNotPermittedException ex) {
      outcome = "circuit_open";
      throw new ChannelUnavailableException("Channel circuit is open for " + channel(), ex);
    } catch (UnsupportedCapabilityException
        | ChannelClientException
        | ChannelIdempotencyConflictException ex) {
      outcome = "client_error";
      throw ex;
    } catch (ChannelServerErrorException ex) {
      outcome = "server_error";
      throw ex;
    } catch (ChannelRateLimitedException ex) {
      outcome = "rate_limited";
      throw ex;
    } catch (ChannelUnavailableException ex) {
      outcome = "unavailable";
      throw ex;
    } catch (RuntimeException ex) {
      outcome = "error";
      throw ex;
    } catch (Exception ex) {
      outcome = "error";
      throw new ChannelUnavailableException("Channel call failed for " + operation, ex);
    } finally {
      metrics.recordDuration(channel(), operation, sample);
      metrics.recordCall(channel(), operation, outcome);
    }
  }

  private <T> T invokeWithRetry(
      ChannelAccountRef account,
      String operation,
      ChannelDeadlineCall<T> httpCall,
      Instant deadline)
      throws Exception {
    ChannelProperties.TsfChannelSettings settings = settings();
    int maxAttempts = Math.max(1, settings.getRetryMaxAttempts());
    CircuitBreaker circuitBreaker =
        resilience.circuitBreaker(channel(), account.channelAccountId());
    ChannelRateLimitedException lastRateLimited = null;
    ChannelUnavailableException lastUnavailable = null;
    for (int attempt = 1; attempt <= maxAttempts; attempt++) {
      ensureBudget(deadline, Duration.ZERO, null);
      try {
        return executeAttempt(account, circuitBreaker, httpCall, deadline);
      } catch (ChannelServerErrorException
          | ChannelClientException
          | ChannelIdempotencyConflictException
          | UnsupportedCapabilityException
          | CallNotPermittedException
          | BulkheadFullException ex) {
        throw ex;
      } catch (ChannelRateLimitedException ex) {
        if (ex.retryAfterSeconds() == null) {
          throw ex;
        }
        lastRateLimited = ex;
        if (attempt >= maxAttempts) {
          throw ex;
        }
        Duration retryAfter =
            ex.retryAfterSeconds() != null && ex.retryAfterSeconds() > 0
                ? Duration.ofSeconds(ex.retryAfterSeconds())
                : Duration.ZERO;
        if (!retryAfter.isZero() && retryAfter.compareTo(settings.getMaxRetryAfter()) > 0) {
          throw ex;
        }
        metrics.recordRetry(channel(), operation, "retry_after");
        Duration sleep = backoffDelay(settings, attempt, retryAfter.isZero() ? null : retryAfter);
        sleepBeforeRetry(deadline, sleep, ex);
      } catch (ChannelUnavailableException ex) {
        lastUnavailable = ex;
        if (attempt >= maxAttempts) {
          throw ex;
        }
        metrics.recordRetry(channel(), operation, "unavailable");
        Duration sleep = backoffDelay(settings, attempt, null);
        sleepBeforeRetry(deadline, sleep, null);
      }
    }
    if (lastRateLimited != null) {
      throw lastRateLimited;
    }
    if (lastUnavailable != null) {
      throw lastUnavailable;
    }
    throw new ChannelUnavailableException("Channel call failed after retries");
  }

  private <T> T executeAttempt(
      ChannelAccountRef account,
      CircuitBreaker circuitBreaker,
      ChannelDeadlineCall<T> httpCall,
      Instant deadline)
      throws Exception {
    Bulkhead bulkhead = resilience.bulkhead(channel());
    RateLimiter rateLimiter = resilience.rateLimiter(channel(), account.channelAccountId());
    acquireBulkhead(bulkhead, deadline);
    try {
      awaitRateLimiter(rateLimiter, deadline);
      return CircuitBreaker.decorateCallable(circuitBreaker, () -> httpCall.call(deadline)).call();
    } finally {
      bulkhead.releasePermission();
    }
  }

  private void acquireBulkhead(Bulkhead bulkhead, Instant deadline) throws InterruptedException {
    Duration maxWait = bulkhead.getBulkheadConfig().getMaxWaitDuration();
    Instant waitUntil =
        clock
            .instant()
            .plus(
                ChannelCallBudget.minDuration(
                    maxWait, ChannelCallBudget.remaining(clock, deadline)));
    metrics.enterBulkheadWait(channel());
    try {
      while (!bulkhead.tryAcquirePermission()) {
        if (!clock.instant().isBefore(waitUntil)) {
          throw BulkheadFullException.createBulkheadFullException(bulkhead);
        }
        try {
          sleeper.sleep(BULKHEAD_POLL_INTERVAL);
        } catch (InterruptedException ex) {
          Thread.currentThread().interrupt();
          throw ex;
        }
      }
    } finally {
      metrics.leaveBulkheadWait(channel());
    }
  }

  private void awaitRateLimiter(RateLimiter rateLimiter, Instant deadline)
      throws InterruptedException {
    Duration remaining = Duration.between(clock.instant(), deadline);
    if (remaining.isZero() || remaining.isNegative()) {
      throw new ChannelUnavailableException("Channel call budget exceeded");
    }
    Duration allowed =
        ChannelCallBudget.minDuration(
            rateLimiter.getRateLimiterConfig().getTimeoutDuration(), remaining);
    long expectedWaitNanos = peekRateLimiterWaitNanos(rateLimiter);
    if (expectedWaitNanos > 0) {
      Duration expectedWait = Duration.ofNanos(expectedWaitNanos);
      if (expectedWait.compareTo(allowed) > 0) {
        throw new ChannelRateLimitedException(
            "Rate limit wait exceeds budget for " + channel(), null);
      }
    }
    long waitNanos = rateLimiter.reservePermission();
    if (waitNanos < 0) {
      throw new ChannelRateLimitedException("Rate limit denied for " + channel(), null);
    }
    if (waitNanos == 0) {
      return;
    }
    Duration wait = Duration.ofNanos(waitNanos);
    if (wait.compareTo(allowed) > 0) {
      throw new ChannelRateLimitedException(
          "Rate limit wait exceeds budget for " + channel(), null);
    }
    try {
      sleeper.sleep(wait);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw ex;
    }
  }

  private static long peekRateLimiterWaitNanos(RateLimiter rateLimiter) {
    if (rateLimiter instanceof AtomicRateLimiter atomic) {
      return atomic.getDetailedMetrics().getNanosToWait();
    }
    return 0L;
  }

  private void sleepBeforeRetry(Instant deadline, Duration sleep, ChannelRateLimitedException rate)
      throws InterruptedException {
    ensureBudget(deadline, sleep, rate);
    if (sleep.isZero()) {
      return;
    }
    try {
      sleeper.sleep(sleep);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw ex;
    }
  }

  private void ensureBudget(
      Instant deadline, Duration nextSleep, ChannelRateLimitedException rate) {
    Instant now = clock.instant();
    if (!now.isBefore(deadline)) {
      if (rate != null) {
        throw rate;
      }
      throw new ChannelUnavailableException("Channel call budget exceeded");
    }
    if (!nextSleep.isZero() && now.plus(nextSleep).compareTo(deadline) > 0) {
      if (rate != null) {
        throw rate;
      }
      throw new ChannelUnavailableException("Channel call budget exceeded");
    }
  }

  private Duration backoffDelay(
      ChannelProperties.TsfChannelSettings settings, int attempt, Duration floor) {
    long baseMs = settings.getRetryWaitBase().toMillis();
    long maxMs = settings.getRetryWaitMax().toMillis();
    long exp = baseMs * (1L << Math.min(attempt - 1, 10));
    long cap = Math.min(exp, maxMs);
    long jitterMs = cap <= 0 ? 0 : ThreadLocalRandom.current().nextLong(cap + 1);
    Duration delay = Duration.ofMillis(jitterMs);
    if (floor != null && floor.compareTo(delay) > 0) {
      delay = floor;
    }
    return delay;
  }
}
