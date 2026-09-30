package com.thaishopfun.oms.stock;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
@EnableConfigurationProperties(StockProperties.class)
public class StockConfig implements InitializingBean {

  private final StockProperties properties;

  public StockConfig(StockProperties properties) {
    this.properties = properties;
  }

  @Override
  public void afterPropertiesSet() {
    // Step 1: Refuse to boot with an unbounded retry or an expiry tick past the 2 minute bound.
    properties.validate();
  }

  /** Own pool so a long expiry run cannot delay the inbox worker or the outbox publisher. */
  @Bean(name = "stockTaskScheduler")
  public ThreadPoolTaskScheduler stockTaskScheduler() {
    ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(1);
    scheduler.setThreadNamePrefix("stock-expiry-");
    scheduler.setRemoveOnCancelPolicy(true);
    return scheduler;
  }

  @Bean
  @ConditionalOnMissingBean
  StockHooks stockHooks() {
    return new StockHooks();
  }
}
