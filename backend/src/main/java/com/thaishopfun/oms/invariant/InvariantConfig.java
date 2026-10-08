package com.thaishopfun.oms.invariant;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
@EnableConfigurationProperties(InvariantProperties.class)
public class InvariantConfig {

  @Bean(name = "invariantTaskScheduler")
  public ThreadPoolTaskScheduler invariantTaskScheduler() {
    ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(1);
    scheduler.setThreadNamePrefix("invariant-");
    scheduler.initialize();
    return scheduler;
  }
}
