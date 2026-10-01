package com.thaishopfun.oms.order;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(OrderProperties.class)
public class OrderConfig {

  @Bean
  @ConditionalOnMissingBean
  OrderIntakeHooks orderIntakeHooks() {
    return new OrderIntakeHooks();
  }
}
