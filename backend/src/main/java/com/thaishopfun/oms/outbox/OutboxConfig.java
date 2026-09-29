package com.thaishopfun.oms.outbox;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
@EnableScheduling
@EnableConfigurationProperties(OutboxProperties.class)
public class OutboxConfig {

  /**
   * The publisher does not share Spring's single-thread scheduler, so an inbox worker can still run
   * while a batch is in flight.
   */
  @Bean(name = "outboxTaskScheduler")
  public ThreadPoolTaskScheduler outboxTaskScheduler() {
    ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(1);
    scheduler.setThreadNamePrefix("outbox-publisher-");
    scheduler.setRemoveOnCancelPolicy(true);
    return scheduler;
  }

  @Bean
  @ConditionalOnMissingBean
  OutboxHooks outboxHooks() {
    return new OutboxHooks();
  }
}
