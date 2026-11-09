package com.thaishopfun.oms.staging;

import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Staging-only demo IdP and CORS. Beans in this configuration are absent outside the {@code
 * staging} profile.
 */
@Configuration
@Profile("staging")
@EnableConfigurationProperties(StagingProperties.class)
public class StagingAuthConfiguration {

  @Bean
  PasswordEncoder stagingPasswordEncoder() {
    return new BCryptPasswordEncoder();
  }

  @Bean
  Clock stagingClock() {
    return Clock.systemUTC();
  }
}
