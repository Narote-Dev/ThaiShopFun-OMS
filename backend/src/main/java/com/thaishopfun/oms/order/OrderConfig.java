package com.thaishopfun.oms.order;

import com.thaishopfun.oms.order.hold.ListingHoldHooks;
import com.thaishopfun.oms.order.hold.OrderHoldProperties;
import com.thaishopfun.oms.order.hold.OrderHoldResolverJob;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
@EnableConfigurationProperties({
  OrderProperties.class,
  OrderIntakeProperties.class,
  OrderHoldProperties.class
})
public class OrderConfig {

  @Bean
  @ConditionalOnMissingBean
  OrderIntakeHooks orderIntakeHooks() {
    return new OrderIntakeHooks();
  }

  @Bean
  @ConditionalOnMissingBean(ListingHoldHooks.class)
  ListingHoldHooks listingHoldHooks(OrderHoldResolverJob job) {
    return job::reevalAfterMapping;
  }

  @Bean(name = "orderTaskScheduler")
  public ThreadPoolTaskScheduler orderTaskScheduler() {
    ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(1);
    scheduler.setThreadNamePrefix("order-hold-");
    scheduler.setRemoveOnCancelPolicy(true);
    return scheduler;
  }
}
