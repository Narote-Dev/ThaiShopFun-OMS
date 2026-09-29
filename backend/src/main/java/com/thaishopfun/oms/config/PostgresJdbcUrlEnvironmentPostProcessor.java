package com.thaishopfun.oms.config;

import java.util.HashMap;
import java.util.Map;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Rewrites Railway's {@code DATABASE_URL} ({@code postgresql://...}) into {@code
 * spring.datasource.url} after {@code application.yml} has been loaded.
 *
 * <p>That URL is the bootstrap login. Flyway uses it too, unless {@code FLYWAY_USER} is set, so a
 * deploy does not fall through to the local dev user {@code oms}.
 */
public class PostgresJdbcUrlEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

  static final String PROPERTY_SOURCE = "postgresJdbcUrl";

  @Override
  public void postProcessEnvironment(
      ConfigurableEnvironment environment, SpringApplication application) {
    // Step 1: Read the resolved datasource URL (yaml placeholder or env).
    String url = environment.getProperty("spring.datasource.url");

    // Step 2: Convert a postgres URL and override user/password embedded in it.
    PostgresJdbcUrl.Normalized normalized = PostgresJdbcUrl.normalize(url);
    if (normalized == null) {
      return;
    }
    Map<String, Object> mapped = new HashMap<>();
    mapped.put("spring.datasource.url", normalized.jdbcUrl());
    if (normalized.username() != null) {
      mapped.put("spring.datasource.username", normalized.username());
    }
    if (normalized.password() != null) {
      mapped.put("spring.datasource.password", normalized.password());
    }
    // Change: Flyway follows the bootstrap login embedded in a postgres URL.
    if (environment.getProperty("FLYWAY_USER") == null && normalized.username() != null) {
      mapped.put("spring.flyway.url", normalized.jdbcUrl());
      mapped.put("spring.flyway.user", normalized.username());
      if (normalized.password() != null) {
        mapped.put("spring.flyway.password", normalized.password());
      }
    }
    environment.getPropertySources().addFirst(new MapPropertySource(PROPERTY_SOURCE, mapped));
  }

  @Override
  public int getOrder() {
    // After ConfigDataEnvironmentPostProcessor so application.yml is visible.
    return Ordered.LOWEST_PRECEDENCE;
  }
}
