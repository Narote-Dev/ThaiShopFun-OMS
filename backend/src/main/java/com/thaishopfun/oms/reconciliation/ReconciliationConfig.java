package com.thaishopfun.oms.reconciliation;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
@EnableScheduling
@EnableConfigurationProperties(ReconciliationProperties.class)
class ReconciliationConfig {

  @Bean(name = "reconciliationTaskScheduler")
  ThreadPoolTaskScheduler reconciliationTaskScheduler() {
    var scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(1);
    scheduler.setThreadNamePrefix("reconciliation-");
    return scheduler;
  }
}
