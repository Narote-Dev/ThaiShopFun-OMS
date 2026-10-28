package com.thaishopfun.oms.stockdrift;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
class StockDriftConfig {
  @Bean(name = "stockDriftTaskScheduler")
  ThreadPoolTaskScheduler stockDriftTaskScheduler() {
    var scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(1);
    scheduler.setThreadNamePrefix("stock-drift-");
    return scheduler;
  }
}
