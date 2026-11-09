package com.thaishopfun.oms.staging;

import com.thaishopfun.oms.auth.ApiErrors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@Profile("staging")
class StagingSecurityConfig {

  @Bean
  @Order(0)
  SecurityFilterChain stagingIdpChain(
      HttpSecurity http, ApiErrors errors, StagingProperties properties) throws Exception {
    String base = properties.getIdpBasePath();
    if (!base.startsWith("/")) {
      base = "/" + base;
    }
    http.securityMatcher(base + "/**");
    http.csrf(AbstractHttpConfigurer::disable)
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
        .exceptionHandling(
            handler ->
                handler
                    .authenticationEntryPoint(errors::unauthorized)
                    .accessDeniedHandler(errors::forbidden));
    return http.build();
  }
}
