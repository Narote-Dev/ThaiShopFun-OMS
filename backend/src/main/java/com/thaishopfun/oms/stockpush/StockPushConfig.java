package com.thaishopfun.oms.stockpush;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
public class StockPushConfig {
  @Bean(name = "stockPushTaskScheduler")
  public ThreadPoolTaskScheduler stockPushTaskScheduler() {
    ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(1);
    scheduler.setThreadNamePrefix("stock-push-");
    return scheduler;
  }
}
