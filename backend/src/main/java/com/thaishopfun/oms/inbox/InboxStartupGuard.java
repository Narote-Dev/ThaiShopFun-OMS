package com.thaishopfun.oms.inbox;

import java.time.Duration;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * HMAC secrets are required outside local and test. An empty list must not fall back to a secret
 * that is committed in the repo.
 */
@Component
@Order(0)
public class InboxStartupGuard implements ApplicationRunner {

  private final Environment environment;
  private final InboxProperties properties;

  public InboxStartupGuard(Environment environment, InboxProperties properties) {
    this.environment = environment;
    this.properties = properties;
  }

  @Override
  public void run(ApplicationArguments args) {
    verify(environment, properties);
  }

  static void verify(Environment environment, InboxProperties properties) {
    // Step 1: Local and test may boot with no secret. Every signature then fails closed.
    boolean localOrTest = environment.acceptsProfiles(Profiles.of("local", "test"));
    if (properties.secrets().isEmpty() && !localOrTest) {
      throw new IllegalStateException(
          "oms.inbox.hmac-secrets is required outside the local and test profiles");
    }
    // Step 2: Compare the lease and the timeout the worker will actually use.
    // claimedLease rounds up to milliseconds. handlerTransactionTimeout ceils to whole seconds.
    Duration lease = InboxLimits.claimedLease(properties.getLease());
    Duration timeout = InboxLimits.handlerTransactionTimeout(properties.getHandlerTimeout());
    if (properties.getBatchSize() < 1
        || properties.getHandlerTimeout().isZero()
        || properties.getHandlerTimeout().isNegative()
        || lease.compareTo(timeout) <= 0) {
      throw new IllegalStateException("oms.inbox handler timeout must be shorter than the lease");
    }
    if (timeout.multipliedBy(properties.getBatchSize()).compareTo(lease) >= 0) {
      throw new IllegalStateException(
          "oms.inbox batch-size times handler-timeout must be shorter than the lease");
    }
    // Step 3: claim_inbox_batch rejects a lease above InboxLimits.MAX_LEASE (1 hour).
    if (lease.compareTo(InboxLimits.MAX_LEASE) > 0) {
      throw new IllegalStateException(
          "oms.inbox.lease must be at most 1 hour (InboxLimits.MAX_LEASE, claim_inbox_batch)");
    }
  }
}
