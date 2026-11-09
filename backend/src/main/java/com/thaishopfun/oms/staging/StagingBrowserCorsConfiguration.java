package com.thaishopfun.oms.staging;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;

@Configuration
@Profile("staging")
class StagingBrowserCorsConfiguration {

  @Bean
  FilterRegistrationBean<StagingBrowserCorsFilter> stagingBrowserCorsRegistration(
      StagingProperties properties) {
    FilterRegistrationBean<StagingBrowserCorsFilter> registration =
        new FilterRegistrationBean<>(new StagingBrowserCorsFilter(properties));
    registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
    registration.addUrlPatterns("/*");
    return registration;
  }
}
