package com.thaishopfun.oms.inbox;

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
    // Step 2: One batch must finish inside the lease, or a later worker can claim the same rows.
    if (properties.getBatchSize() < 1
        || properties.getHandlerTimeout().isZero()
        || properties.getHandlerTimeout().isNegative()
        || properties.getLease().compareTo(properties.getHandlerTimeout()) <= 0) {
      throw new IllegalStateException("oms.inbox handler timeout must be shorter than the lease");
    }
    if (properties
            .getHandlerTimeout()
            .multipliedBy(properties.getBatchSize())
            .compareTo(properties.getLease())
        >= 0) {
      throw new IllegalStateException(
          "oms.inbox batch-size times handler-timeout must be shorter than the lease");
    }
  }
}
