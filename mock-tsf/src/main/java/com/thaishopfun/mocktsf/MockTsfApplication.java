package com.thaishopfun.mocktsf;

import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication(
    excludeName = {
      // Change: this process has no database. OMS tests put JDBC and Flyway on the shared
      // classpath.
      "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
      "org.springframework.boot.jdbc.autoconfigure.DataSourceInitializationAutoConfiguration",
      "org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration",
      "org.springframework.boot.jdbc.autoconfigure.JdbcClientAutoConfiguration",
      "org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration",
      "org.springframework.boot.jdbc.autoconfigure.JndiDataSourceAutoConfiguration",
      "org.springframework.boot.jdbc.autoconfigure.XADataSourceAutoConfiguration",
      "org.springframework.boot.jdbc.autoconfigure.health.DataSourceHealthContributorAutoConfiguration",
      "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration",
      "org.springframework.boot.flyway.autoconfigure.FlywayEndpointAutoConfiguration"
    })
@ConfigurationPropertiesScan
public class MockTsfApplication {

  public static void main(String[] args) {
    application().run(args);
  }

  /**
   * {@code mock-tsf.yml} so this jar does not override OMS {@code application.yml} on the test
   * classpath.
   */
  public static SpringApplication application() {
    SpringApplication app = new SpringApplication(MockTsfApplication.class);
    app.setDefaultProperties(Map.of("spring.config.name", "mock-tsf"));
    return app;
  }
}
