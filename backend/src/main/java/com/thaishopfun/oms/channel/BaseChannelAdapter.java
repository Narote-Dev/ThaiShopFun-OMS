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
import com.thaishopfun.oms.channel.exception.ChannelUnavailableException;
import com.thaishopfun.oms.channel.exception.UnsupportedCapabilityException;
import io.github.resilience4j.bulkhead.Bulkhead;
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
 * Decorator order: capability check → retry loop (bulkhead per attempt → rate limiter → circuit
 * breaker → HTTP). Backoff and Retry-After sleeps run outside the bulkhead. Total wall time including
 * sleeps is capped by {@link ChannelProperties.TsfChannelSettings#getHttpTimeout()}.
 */
public abstract class BaseChannelAdapter implements ChannelAdapter {

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
      throw new ChannelClientException(400, "IDEMPOTENCY_KEY_REQUIRED", "Idempotency-Key is required");
    }
  }

  private <T> T invoke(ChannelAccountRef account, String operation, Callable<T> httpCall) {
    Timer.Sample sample = metrics.startTimer();
    String outcome = "success";
    Instant deadline = clock.instant().plus(settings().getHttpTimeout());
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
      ChannelAccountRef account, String operation, Callable<T> httpCall, Instant deadline)
      throws Exception {
    ChannelProperties.TsfChannelSettings settings = settings();
    int maxAttempts = Math.max(1, settings.getRetryMaxAttempts());
    CircuitBreaker circuitBreaker =
        resilience.circuitBreaker(channel(), account.channelAccountId());
    Exception last = null;
    for (int attempt = 1; attempt <= maxAttempts; attempt++) {
      ensureBudget(deadline, Duration.ZERO, null);
      Bulkhead bulkhead = resilience.bulkhead(channel());
      metrics.enterBulkheadWait(channel());
      try {
        bulkhead.acquirePermission();
      } finally {
        metrics.leaveBulkheadWait(channel());
      }
      try {
        RateLimiter rateLimiter = resilience.rateLimiter(channel(), account.channelAccountId());
        try {
          return RateLimiter.decorateCallable(
                  rateLimiter,
                  () ->
                      CircuitBreaker.decorateCallable(circuitBreaker, httpCall::call).call())
              .call();
        } catch (RequestNotPermitted ex) {
          throw new ChannelRateLimitedException("Rate limit wait exceeded for " + operation, null);
        }
      } catch (ChannelClientException | ChannelIdempotencyConflictException | UnsupportedCapabilityException ex) {
        throw ex;
      } catch (ChannelRateLimitedException ex) {
        metrics.recordRetry(channel(), operation, "retry_after");
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
        Duration sleep = backoffDelay(settings, attempt, retryAfter.isZero() ? null : retryAfter);
        sleepBeforeRetry(deadline, sleep, ex);
      } catch (CallNotPermittedException ex) {
        throw new ChannelUnavailableException("Channel circuit is open for " + channel(), ex);
      } catch (BulkheadFullException ex) {
        throw new ChannelUnavailableException("Channel bulkhead is full for " + channel(), ex);
      } catch (ChannelUnavailableException ex) {
        last = ex;
        metrics.recordRetry(channel(), operation, "unavailable");
        if (attempt >= maxAttempts) {
          throw ex;
        }
        Duration sleep = backoffDelay(settings, attempt, null);
        sleepBeforeRetry(deadline, sleep, null);
      } catch (RuntimeException ex) {
        throw ex;
      } catch (Exception ex) {
        throw new ChannelUnavailableException("Channel call failed for " + operation, ex);
      } finally {
        bulkhead.releasePermission();
      }
    }
    if (last instanceof RuntimeException runtime) {
      throw runtime;
    }
    throw new ChannelUnavailableException("Channel call failed after retries", last);
  }

  private void sleepBeforeRetry(Instant deadline, Duration sleep, ChannelRateLimitedException rate)
      throws InterruptedException {
    if (sleep.isZero()) {
      return;
    }
    ensureBudget(deadline, sleep, rate);
    sleeper.sleep(sleep);
  }

  private void ensureBudget(Instant deadline, Duration nextSleep, ChannelRateLimitedException rate) {
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

  protected Duration httpTimeout() {
    return settings().getHttpTimeout();
  }

  protected Clock clock() {
    return clock;
  }
}
