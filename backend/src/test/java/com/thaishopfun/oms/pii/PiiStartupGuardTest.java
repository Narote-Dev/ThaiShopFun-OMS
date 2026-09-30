package com.thaishopfun.oms.pii;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.thaishopfun.oms.OmsApplication;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The default profile has no PII keys, so the app must refuse to boot until they are configured. No
 * {@code test} profile here, so {@code application-test.yml} (which has test keys) is not read.
 */
class PiiStartupGuardTest {

  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

  static {
    POSTGRES.start();
  }

  @Test
  void defaultProfileWithoutKeysRefusesToStart() {
    assertThatThrownBy(() -> SpringApplication.run(OmsApplication.class, args("", "", "")))
        .hasStackTraceContaining("oms.pii.keys (OMS_PII_KEYS) is required");
  }

  @Test
  void defaultProfileWithoutHashKeyRefusesToStart() {
    assertThatThrownBy(
            () ->
                SpringApplication.run(
                    OmsApplication.class, args("k1:" + PiiCipherTest.KEY_1, "k1", "")))
        .hasStackTraceContaining("oms.pii.hash-key (OMS_PII_HASH_KEY) is required");
  }

  @Test
  void defaultProfileStartsOnceKeysAreConfigured() {
    try (ConfigurableApplicationContext context =
        SpringApplication.run(
            OmsApplication.class,
            args("k1:" + PiiCipherTest.KEY_1, "k1", PiiCipherTest.HASH_KEY))) {
      assertThat(context.getBean(PiiCipher.class).activeKeyId()).isEqualTo("k1");
    }
  }

  @Test
  void guardAcceptsAValidRing() {
    assertThat(PiiStartupGuard.verify(PiiCipherTest.properties("k1:" + PiiCipherTest.KEY_1, "k1")))
        .extracting(PiiKeyRing::keyIds)
        .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.COLLECTION)
        .containsExactly("k1");
  }

  // Superuser login, so the RLS bypass is allowed explicitly. That guard is not under test here.
  private static String[] args(String keys, String activeKeyId, String hashKey) {
    List<String> args =
        new ArrayList<>(
            List.of(
                "--oms.pii.keys=" + keys,
                "--oms.pii.active-key-id=" + activeKeyId,
                "--oms.pii.hash-key=" + hashKey,
                "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                "--spring.datasource.username=" + POSTGRES.getUsername(),
                "--spring.datasource.password=" + POSTGRES.getPassword(),
                "--spring.flyway.url=" + POSTGRES.getJdbcUrl(),
                "--spring.flyway.user=" + POSTGRES.getUsername(),
                "--spring.flyway.password=" + POSTGRES.getPassword(),
                "--server.port=0",
                "--oms.inbox.worker-enabled=false",
                "--oms.inbox.hmac-secrets=startup-test-only",
                "--oms.stock.expiry.enabled=false",
                "--oms.security.allow-rls-bypass=true"));
    return args.toArray(String[]::new);
  }
}
