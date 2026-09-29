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
   * Default pool for {@code @Scheduled} methods that do not name a scheduler, including the T11
   * inbox worker. Defining only the outbox scheduler would make Boot skip this bean, and Spring
   * would then run every scheduled method on the outbox pool.
   */
  @Bean(name = "taskScheduler")
  public ThreadPoolTaskScheduler taskScheduler() {
    ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(1);
    scheduler.setThreadNamePrefix("task-scheduler-");
    return scheduler;
  }

  /** Own pool for the outbox publisher so a long tick cannot block the inbox worker. */
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
