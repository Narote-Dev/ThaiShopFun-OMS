package com.thaishopfun.oms.inbox;

import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(InboxProperties.class)
public class InboxConfig {

  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }
}
