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
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

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
    // Step 1: Ant-style matcher so OPTIONS preflight matches before MVC maps POST-only handlers.
    http.securityMatcher(PathPatternRequestMatcher.pathPattern(base + "/**"));
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
