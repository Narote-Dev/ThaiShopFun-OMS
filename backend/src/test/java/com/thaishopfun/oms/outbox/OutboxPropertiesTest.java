package com.thaishopfun.oms.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class OutboxPropertiesTest {

  @Test
  void defaultsAreValidWhenTheDestinationIsBlank() {
    assertThatCode(() -> new OutboxProperties().validate()).doesNotThrowAnyException();
  }

  @Test
  void rejectsAShortLeaseAShortSecretABadUrlAndATightBatch() {
    OutboxProperties lease = configured();
    lease.setLease(Duration.ofMillis(500));
    assertThatThrownBy(lease::validate).hasMessageContaining("at least 1s");

    OutboxProperties secret = configured();
    secret.setWebhookSecret("short-secret");
    assertThatThrownBy(secret::validate).hasMessageContaining("32 bytes");

    OutboxProperties url = configured();
    url.setDestinationUrl("ftp://files.example/hook");
    assertThatThrownBy(url::validate).hasMessageContaining("absolute http");

    OutboxProperties relative = configured();
    relative.setDestinationUrl("/internal/v1/oms-events");
    assertThatThrownBy(relative::validate).hasMessageContaining("absolute http");

    OutboxProperties tight = configured();
    tight.setBatchSize(20);
    tight.setHttpTimeout(Duration.ofSeconds(10));
    tight.setLease(Duration.ofSeconds(210));
    assertThatThrownBy(tight::validate).hasMessageContaining("margin");

    OutboxProperties ok = configured();
    ok.setLease(Duration.ofSeconds(211));
    assertThatCode(ok::validate).doesNotThrowAnyException();
  }

  @Test
  void redirectAndOtherClientErrorsAreDead() {
    assertThat(new OutboxHttpSender.SendResult(302, null).dead()).isTrue();
    assertThat(new OutboxHttpSender.SendResult(400, null).dead()).isTrue();
    assertThat(new OutboxHttpSender.SendResult(408, null).dead()).isFalse();
    assertThat(new OutboxHttpSender.SendResult(429, null).dead()).isFalse();
    assertThat(new OutboxHttpSender.SendResult(500, null).dead()).isFalse();
    assertThat(new OutboxHttpSender.SendResult(202, null).success()).isTrue();
  }

  private static OutboxProperties configured() {
    OutboxProperties properties = new OutboxProperties();
    properties.setDestinationUrl("https://example.test/internal/v1/oms-events");
    properties.setWebhookSecret("test-outbox-secret-0123456789abcdef");
    return properties;
  }
}
