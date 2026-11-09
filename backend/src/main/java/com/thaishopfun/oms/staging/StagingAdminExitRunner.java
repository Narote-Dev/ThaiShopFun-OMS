package com.thaishopfun.oms.staging;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** One-shot admin CLI: seed demo data and exit (used from Railway shell or CI smoke). */
@Component
@Profile("staging")
@Order(100)
@ConditionalOnProperty(name = "oms.staging.admin-seed-demo", havingValue = "true")
class StagingAdminExitRunner implements ApplicationRunner {

  private final ApplicationContext context;

  StagingAdminExitRunner(ApplicationContext context) {
    this.context = context;
  }

  @Override
  public void run(ApplicationArguments args) {
    int code = SpringApplication.exit(context, () -> 0);
    System.exit(code);
  }
}
