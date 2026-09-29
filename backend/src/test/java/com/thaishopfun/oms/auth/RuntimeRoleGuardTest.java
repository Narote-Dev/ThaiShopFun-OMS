package com.thaishopfun.oms.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.thaishopfun.oms.OmsApplication;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.postgresql.PostgreSQLContainer;

class RuntimeRoleGuardTest {

  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

  static {
    POSTGRES.start();
  }

  @Test
  void superuserFailsStartupWhenBypassIsNotAllowed() {
    assertThatThrownBy(() -> SpringApplication.run(OmsApplication.class, args(false)))
        .hasMessageContaining("bypasses row-level security");
  }

  @Test
  void superuserStartsWhenBypassIsExplicitlyAllowed() {
    try (ConfigurableApplicationContext context =
        SpringApplication.run(OmsApplication.class, args(true))) {
      assertThat(context.isRunning()).isTrue();
    }
  }

  private static String[] args(boolean allowBypass) {
    return new String[] {
      "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
      "--spring.datasource.username=" + POSTGRES.getUsername(),
      "--spring.datasource.password=" + POSTGRES.getPassword(),
      "--spring.flyway.url=" + POSTGRES.getJdbcUrl(),
      "--spring.flyway.user=" + POSTGRES.getUsername(),
      "--spring.flyway.password=" + POSTGRES.getPassword(),
      "--server.port=0",
      "--oms.security.allow-rls-bypass=" + allowBypass
    };
  }
}
