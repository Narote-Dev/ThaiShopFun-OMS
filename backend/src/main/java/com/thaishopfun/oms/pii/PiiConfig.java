package com.thaishopfun.oms.pii;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(PiiProperties.class)
public class PiiConfig {

  /** Parsing fails the context refresh, so a missing or invalid key stops the app booting. */
  @Bean
  public PiiCipher piiCipher(PiiProperties properties) {
    return new PiiCipher(PiiStartupGuard.verify(properties));
  }
}
