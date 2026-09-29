package com.thaishopfun.oms.inbox;

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
