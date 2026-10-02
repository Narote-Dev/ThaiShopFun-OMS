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
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Callable;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BooleanSupplier;

/**
 * Decorator order: capability check → retry loop. Each attempt runs bulkhead → rate limiter →
 * circuit breaker → HTTP, then releases the bulkhead permit before any retry sleep. Total wall time
 * for waits, HTTP, token fetch, and backoff is capped by {@link
 * ChannelProperties.TsfChannelSettings#getHttpTimeout()}.
 */
public abstract class BaseChannelAdapter implements ChannelAdapter {

  private final AccountResilienceRegistry resilience;
  private final ChannelProperties properties;
  private final ChannelMetrics metrics;
  private final Sleeper sleeper;
  private final Clock clock;

  private Instant activeDeadline;

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
    return invoke(account, "listOrders", () -> doListOrders(account, updatedSince, cursor, limit));
  }

  @Override
  public final OrderDetail getOrder(ChannelAccountRef account, String externalOrderId) {
    requireCapability("getOrder", capabilities()::supportsOrderPull);
    return invoke(account, "getOrder", () -> doGetOrder(account, externalOrderId));
  }

  @Override
  public final PaymentStatus getPaymentStatus(ChannelAccountRef account, String externalOrderId) {
    requireCapability("getPaymentStatus", capabilities()::supportsOrderPull);
    return invoke(account, "getPaymentStatus", () -> doGetPaymentStatus(account, externalOrderId));
  }

  @Override
  public final ListingPage listListings(ChannelAccountRef account, String cursor) {
    requireCapability("listListings", capabilities()::supportsStockPush);
    return invoke(account, "listListings", () -> doListListings(account, cursor));
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
        () -> doCreateShipment(account, externalOrderId, idempotencyKey, request));
  }

  @Override
  public final LabelContent getLabel(ChannelAccountRef account, String shipmentId) {
    requireCapability("getLabel", capabilities()::supportsLabel);
    return invoke(account, "getLabel", () -> doGetLabel(account, shipmentId));
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
        () -> doRequestCancel(account, externalOrderId, idempotencyKey, request));
  }

  protected abstract OrderPage doListOrders(
      ChannelAccountRef account, Instant updatedSince, String cursor, int limit);

  protected abstract OrderDetail doGetOrder(ChannelAccountRef account, String externalOrderId);

  protected abstract PaymentStatus doGetPaymentStatus(
      ChannelAccountRef account, String externalOrderId);

  protected abstract ListingPage doListListings(ChannelAccountRef account, String cursor);

  protected abstract Shipment doCreateShipment(
      ChannelAccountRef account,
      String externalOrderId,
      String idempotencyKey,
      ShipmentRequest request);

  protected abstract LabelContent doGetLabel(ChannelAccountRef account, String shipmentId);

  protected abstract CancelResponse doRequestCancel(
      ChannelAccountRef account,
      String externalOrderId,
      String idempotencyKey,
      CancelRequest request);

  protected ChannelProperties.TsfChannelSettings settings() {
    return properties.settingsFor(channel());
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

  private <T> T invoke(ChannelAccountRef account, String operation, Callable<T> httpCall) {
    Timer.Sample sample = metrics.startTimer();
    String outcome = "success";
    Instant deadline = clock.instant().plus(settings().getHttpTimeout());
    activeDeadline = deadline;
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
      outcome = "client_error";
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
      activeDeadline = null;
      metrics.recordDuration(channel(), operation, sample);
      metrics.recordCall(channel(), operation, outcome);
    }
  }

  private <T> T invokeWithRetry(
      ChannelAccountRef account, String operation, Callable<T> httpCall, Instant deadline)
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
      Callable<T> httpCall,
      Instant deadline)
      throws Exception {
    Duration remaining = remainingBudget(deadline);
    Bulkhead bulkhead = resilience.bulkhead(channel());
    RateLimiter rateLimiter = resilience.rateLimiter(channel(), account.channelAccountId());

    Duration bulkheadWait =
        minDuration(bulkhead.getBulkheadConfig().getMaxWaitDuration(), remaining);
    BulkheadConfig bulkheadConfig = bulkhead.getBulkheadConfig();
    boolean bulkheadConfigChanged = false;
    synchronized (bulkhead) {
      if (bulkheadWait.compareTo(bulkheadConfig.getMaxWaitDuration()) < 0) {
        bulkhead.changeConfig(
            BulkheadConfig.from(bulkheadConfig).maxWaitDuration(bulkheadWait).build());
        bulkheadConfigChanged = true;
      }
      metrics.enterBulkheadWait(channel());
      try {
        bulkhead.acquirePermission();
      } finally {
        metrics.leaveBulkheadWait(channel());
        if (bulkheadConfigChanged) {
          bulkhead.changeConfig(bulkheadConfig);
        }
      }
    }

    Duration rateWait =
        minDuration(rateLimiter.getRateLimiterConfig().getTimeoutDuration(), remaining);
    Duration previousRateTimeout = rateLimiter.getRateLimiterConfig().getTimeoutDuration();
    boolean rateTimeoutChanged = false;
    synchronized (rateLimiter) {
      if (rateWait.compareTo(previousRateTimeout) < 0) {
        rateLimiter.changeTimeoutDuration(rateWait);
        rateTimeoutChanged = true;
      }
      try {
        if (!rateLimiter.acquirePermission()) {
          throw new ChannelRateLimitedException("Rate limit wait exceeded for " + channel(), null);
        }
        return CircuitBreaker.decorateCallable(circuitBreaker, httpCall::call).call();
      } catch (RequestNotPermitted ex) {
        throw new ChannelRateLimitedException("Rate limit wait exceeded for " + channel(), null);
      } finally {
        if (rateTimeoutChanged) {
          rateLimiter.changeTimeoutDuration(previousRateTimeout);
        }
        bulkhead.releasePermission();
      }
    }
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

  private Duration remainingBudget(Instant deadline) {
    Duration remaining = Duration.between(clock.instant(), deadline);
    if (remaining.isNegative() || remaining.isZero()) {
      throw new ChannelUnavailableException("Channel call budget exceeded");
    }
    return remaining;
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

  private static Duration minDuration(Duration a, Duration b) {
    return a.compareTo(b) <= 0 ? a : b;
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

  /** Remaining call budget capped by configured HTTP timeout (for transport and token). */
  protected Duration transportTimeout() {
    ChannelProperties.TsfChannelSettings settings = settings();
    Duration configured = settings.getHttpTimeout();
    Instant deadline = activeDeadline;
    if (deadline == null) {
      return configured;
    }
    Duration remaining = Duration.between(clock.instant(), deadline);
    if (remaining.isNegative() || remaining.isZero()) {
      throw new ChannelUnavailableException("Channel call budget exceeded");
    }
    return minDuration(configured, remaining);
  }

  protected Duration httpTimeout() {
    return transportTimeout();
  }

  protected Clock clock() {
    return clock;
  }
}
