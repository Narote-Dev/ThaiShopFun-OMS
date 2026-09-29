package com.thaishopfun.oms.inbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class InboxStartupGuardTest {

  @Test
  void emptySecretsRefuseToBootOutsideLocalAndTest() {
    InboxProperties properties = new InboxProperties();
    assertThatThrownBy(() -> InboxStartupGuard.verify(new MockEnvironment(), properties))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("hmac-secrets");
  }

  @Test
  void emptySecretsAreAllowedOnTheTestProfile() {
    MockEnvironment environment = new MockEnvironment();
    environment.setActiveProfiles("test");
    assertThatCode(() -> InboxStartupGuard.verify(environment, new InboxProperties()))
        .doesNotThrowAnyException();
  }

  @Test
  void configuredSecretBootsWithoutALocalProfile() {
    InboxProperties properties = new InboxProperties();
    properties.setHmacSecrets("prod-secret");
    assertThatCode(() -> InboxStartupGuard.verify(new MockEnvironment(), properties))
        .doesNotThrowAnyException();
  }

  @Test
  void leaseLongerThanOneHourRefusesToBoot() {
    MockEnvironment environment = new MockEnvironment();
    environment.setActiveProfiles("test");
    InboxProperties properties = new InboxProperties();
    properties.setBatchSize(1);
    properties.setHandlerTimeout(Duration.ofSeconds(30));
    properties.setLease(InboxLimits.MAX_LEASE.plusSeconds(1));
    assertThatThrownBy(() -> InboxStartupGuard.verify(environment, properties))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("1 hour")
        .hasMessageContaining("InboxLimits.MAX_LEASE");
  }

  @Test
  void handlerTransactionTimeoutRoundsUpToWholeSeconds() {
    assertThat(InboxLimits.handlerTransactionTimeout(Duration.ofMillis(1100)))
        .isEqualTo(Duration.ofSeconds(2));
    assertThat(InboxLimits.handlerTransactionTimeoutSeconds(Duration.ofMillis(1100))).isEqualTo(2);
    assertThat(InboxLimits.handlerTransactionTimeout(Duration.ofSeconds(2)))
        .isEqualTo(Duration.ofSeconds(2));
    assertThat(InboxLimits.handlerTransactionTimeout(Duration.ofMillis(400)))
        .isEqualTo(Duration.ofSeconds(1));
  }

  @Test
  void subsecondLeaseIsKeptAboveTheHandlerTimeout() {
    MockEnvironment environment = new MockEnvironment();
    environment.setActiveProfiles("test");
    InboxProperties properties = new InboxProperties();
    properties.setBatchSize(1);
    properties.setHandlerTimeout(Duration.ofMillis(1100));
    properties.setLease(Duration.ofMillis(1200));
    assertThat(InboxLimits.claimedLease(properties.getLease())).isEqualTo(Duration.ofMillis(1200));
    // 1.1s becomes a 2s transaction timeout, which does not fit in a 1.2s lease.
    assertThatThrownBy(() -> InboxStartupGuard.verify(environment, properties))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("lease");

    properties.setLease(Duration.ofSeconds(3));
    assertThatCode(() -> InboxStartupGuard.verify(environment, properties))
        .doesNotThrowAnyException();
  }

  @Test
  void leaseOfOneHourStillBoots() {
    MockEnvironment environment = new MockEnvironment();
    environment.setActiveProfiles("test");
    InboxProperties properties = new InboxProperties();
    properties.setBatchSize(1);
    properties.setHandlerTimeout(Duration.ofSeconds(30));
    properties.setLease(InboxLimits.MAX_LEASE);
    assertThatCode(() -> InboxStartupGuard.verify(environment, properties))
        .doesNotThrowAnyException();
  }

  @Test
  void batchThatOutlastsTheLeaseRefusesToBoot() {
    MockEnvironment environment = new MockEnvironment();
    environment.setActiveProfiles("test");
    InboxProperties properties = new InboxProperties();
    properties.setBatchSize(50);
    properties.setHandlerTimeout(Duration.ofSeconds(30));
    properties.setLease(Duration.ofMinutes(5));
    assertThatThrownBy(() -> InboxStartupGuard.verify(environment, properties))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("lease");
  }
}
