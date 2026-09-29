package com.thaishopfun.oms.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.env.MockEnvironment;

class PostgresJdbcUrlEnvironmentPostProcessorTest {

  @Test
  void railwayUrlConfiguresFlywayWithTheBootstrapLogin() {
    MockEnvironment environment = new MockEnvironment();
    environment.setProperty(
        "spring.datasource.url", "postgresql://postgres:secret@db.internal:5432/railway");

    new PostgresJdbcUrlEnvironmentPostProcessor()
        .postProcessEnvironment(environment, new SpringApplication());

    assertThat(environment.getProperty("spring.datasource.url"))
        .isEqualTo("jdbc:postgresql://db.internal:5432/railway");
    assertThat(environment.getProperty("spring.datasource.username")).isEqualTo("postgres");
    assertThat(environment.getProperty("spring.flyway.url"))
        .isEqualTo("jdbc:postgresql://db.internal:5432/railway");
    assertThat(environment.getProperty("spring.flyway.user")).isEqualTo("postgres");
    assertThat(environment.getProperty("spring.flyway.password")).isEqualTo("secret");
  }

  @Test
  void explicitFlywayUserIsLeftAlone() {
    MockEnvironment environment = new MockEnvironment();
    environment.setProperty(
        "spring.datasource.url", "postgresql://postgres:secret@db.internal:5432/railway");
    environment.setProperty("FLYWAY_USER", "custom_migrator");

    new PostgresJdbcUrlEnvironmentPostProcessor()
        .postProcessEnvironment(environment, new SpringApplication());

    MapPropertySource source =
        (MapPropertySource)
            environment
                .getPropertySources()
                .get(PostgresJdbcUrlEnvironmentPostProcessor.PROPERTY_SOURCE);
    assertThat(source).isNotNull();
    Map<String, Object> mapped = source.getSource();
    assertThat(mapped).doesNotContainKey("spring.flyway.user");
    assertThat(environment.getProperty("spring.datasource.username")).isEqualTo("postgres");
  }

  @Test
  void explicitDatabaseUserWinsOverUrlCredentials() {
    MockEnvironment environment = new MockEnvironment();
    environment.setProperty(
        "spring.datasource.url", "postgresql://postgres:secret@db.internal:5432/railway");
    environment.setProperty("DATABASE_USERNAME", "oms_app");
    environment.setProperty("DATABASE_PASSWORD", "app-secret");
    environment.setProperty("spring.datasource.username", "oms_app");
    environment.setProperty("spring.datasource.password", "app-secret");

    new PostgresJdbcUrlEnvironmentPostProcessor()
        .postProcessEnvironment(environment, new SpringApplication());

    assertThat(environment.getProperty("spring.datasource.url"))
        .isEqualTo("jdbc:postgresql://db.internal:5432/railway");
    assertThat(environment.getProperty("spring.datasource.username")).isEqualTo("oms_app");
    assertThat(environment.getProperty("spring.datasource.password")).isEqualTo("app-secret");
    assertThat(environment.getProperty("spring.flyway.user")).isEqualTo("postgres");
    assertThat(environment.getProperty("spring.flyway.password")).isEqualTo("secret");
  }
}
