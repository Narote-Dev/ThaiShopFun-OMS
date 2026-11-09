package com.thaishopfun.oms.staging;

import com.thaishopfun.oms.order.demo.OrderDemoCatalogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Idempotent demo catalog seed for staging trials (reuses local mock order catalog). */
@Component
@Profile("staging")
@Order(50)
class StagingDemoSeedRunner implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(StagingDemoSeedRunner.class);

  private final StagingProperties properties;
  private final OrderDemoCatalogService demoCatalog;

  StagingDemoSeedRunner(StagingProperties properties, OrderDemoCatalogService demoCatalog) {
    this.properties = properties;
    this.demoCatalog = demoCatalog;
  }

  @Override
  public void run(ApplicationArguments args) {
    if (!properties.isDemoSeedOnStartup() && !properties.isAdminSeedDemo()) {
      return;
    }
    // Step 1: Reuse the same idempotent catalog rows as local mock-tsf demos.
    demoCatalog.ensureDemoCatalog();
    log.info("staging demo catalog seed completed");
  }
}
