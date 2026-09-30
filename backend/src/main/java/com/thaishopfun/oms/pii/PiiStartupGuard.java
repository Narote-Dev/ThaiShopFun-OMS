package com.thaishopfun.oms.pii;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * The PII key ring and hash key are required in every profile. Only {@code application-local.yml}
 * and the test config carry dev-only keys; the default profile has none, so a deploy without {@code
 * OMS_PII_KEYS}, {@code OMS_PII_ACTIVE_KEY_ID}, and {@code OMS_PII_HASH_KEY} refuses to boot.
 * {@link PiiConfig} already fails the context refresh; this runner re-checks the bound properties.
 */
@Component
@Order(0)
public class PiiStartupGuard implements ApplicationRunner {

  private final PiiProperties properties;

  public PiiStartupGuard(PiiProperties properties) {
    this.properties = properties;
  }

  @Override
  public void run(ApplicationArguments args) {
    verify(properties);
  }

  static PiiKeyRing verify(PiiProperties properties) {
    // Step 1: No profile is exempt. Parsing throws IllegalStateException without key values.
    return PiiKeyRing.parse(properties);
  }
}
