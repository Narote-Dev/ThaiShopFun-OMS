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
    String url = environment.getProperty("spring.datasource.url");
    PostgresJdbcUrl.Normalized normalized = PostgresJdbcUrl.normalize(url);
    if (normalized == null) {
      return;
    }
    Map<String, Object> mapped = new HashMap<>();
    mapped.put("spring.datasource.url", normalized.jdbcUrl());
    if (!explicitRuntimeUser(environment) && normalized.username() != null) {
      mapped.put("spring.datasource.username", normalized.username());
    }
    if (!explicitRuntimePassword(environment) && normalized.password() != null) {
      mapped.put("spring.datasource.password", normalized.password());
    }
    if (environment.getProperty("FLYWAY_USER") == null && normalized.username() != null) {
      mapped.put("spring.flyway.url", normalized.jdbcUrl());
      mapped.put("spring.flyway.user", normalized.username());
      if (normalized.password() != null) {
        mapped.put("spring.flyway.password", normalized.password());
      }
    }
    environment.getPropertySources().addFirst(new MapPropertySource(PROPERTY_SOURCE, mapped));
  }

  static boolean explicitRuntimeUser(ConfigurableEnvironment environment) {
    return hasText(environment.getProperty("DATABASE_USERNAME"))
        || definedInSystemSource(environment, "spring.datasource.username");
  }

  static boolean explicitRuntimePassword(ConfigurableEnvironment environment) {
    return hasText(environment.getProperty("DATABASE_PASSWORD"))
        || definedInSystemSource(environment, "spring.datasource.password");
  }

  private static boolean definedInSystemSource(ConfigurableEnvironment environment, String key) {
    for (org.springframework.core.env.PropertySource<?> source : environment.getPropertySources()) {
      String name = source.getName();
      if (("systemProperties".equals(name) || "systemEnvironment".equals(name))
          && source.containsProperty(key)) {
        return true;
      }
    }
    return false;
  }

  private static boolean hasText(String value) {
    return value != null && !value.isBlank();
  }

  @Override
  public int getOrder() {
    return Ordered.LOWEST_PRECEDENCE;
  }
}
