package com.thaishopfun.oms.order.demo;

import com.thaishopfun.oms.auth.ApiErrors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/** Local/e2e harness endpoints (not part of the public API contract). */
@Configuration
@Profile({"local", "e2e", "test"})
class DemoSecurityConfig {

  @Bean
  @Order(0)
  SecurityFilterChain demoControlChain(HttpSecurity http, ApiErrors errors) throws Exception {
    http.securityMatcher("/control/demo/**");
    http.csrf(csrf -> csrf.disable());
    http.authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
    http.exceptionHandling(
        handler ->
            handler
                .authenticationEntryPoint(errors::unauthorized)
                .accessDeniedHandler(errors::forbidden));
    return http.build();
  }
}
