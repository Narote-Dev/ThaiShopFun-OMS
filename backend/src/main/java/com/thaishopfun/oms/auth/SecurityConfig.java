package com.thaishopfun.oms.auth;

import java.util.Optional;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfigurationSource;

@Configuration
@EnableConfigurationProperties(OmsSecurityProperties.class)
public class SecurityConfig {

  @Bean
  @Profile("!staging")
  JwtDecoder jwtDecoder(OmsSecurityProperties properties) {
    return JwtDecoderSupport.singleIssuerDecoder(properties);
  }

  @Bean
  @Order(1)
  SecurityFilterChain internalChain(
      HttpSecurity http, JwtDecoder jwtDecoder, ApiErrors errors, OmsSecurityProperties properties)
      throws Exception {
    stateless(http, errors, Optional.empty());
    http.securityMatcher("/internal/**");
    http.authorizeHttpRequests(auth -> auth.anyRequest().authenticated());
    oauth(http, jwtDecoder, errors);
    http.addFilterAfter(
        new RequiredAudienceFilter(
            errors, properties.getInternalAudience(), properties.getInternalClientIds()),
        BearerTokenAuthenticationFilter.class);
    return http.build();
  }

  @Bean
  @Order(2)
  SecurityFilterChain apiChain(
      HttpSecurity http,
      JwtDecoder jwtDecoder,
      ApiErrors errors,
      OmsSecurityProperties properties,
      IdentityProvisioner provisioner,
      TenantSessionService sessions,
      EntitlementGate gate)
      throws Exception {
    stateless(http, errors, Optional.empty());
    http.securityMatcher("/api/**");
    http.authorizeHttpRequests(auth -> auth.anyRequest().authenticated());
    oauth(http, jwtDecoder, errors);
    http.addFilterAfter(
        new TenantContextFilter(provisioner, sessions, gate, errors, properties.getAudience()),
        BearerTokenAuthenticationFilter.class);
    return http.build();
  }

  @Bean
  @Order(3)
  SecurityFilterChain publicChain(HttpSecurity http, ApiErrors errors) throws Exception {
    stateless(http, errors, Optional.empty());
    http.securityMatcher("/**");
    http.authorizeHttpRequests(
        auth -> auth.requestMatchers("/actuator/**", "/error").permitAll().anyRequest().denyAll());
    return http.build();
  }

  private static void stateless(
      HttpSecurity http, ApiErrors errors, Optional<CorsConfigurationSource> cors)
      throws Exception {
    http.csrf(AbstractHttpConfigurer::disable)
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .exceptionHandling(
            handler ->
                handler
                    .authenticationEntryPoint(errors::unauthorized)
                    .accessDeniedHandler(errors::forbidden));
    if (cors.isPresent()) {
      http.cors(c -> c.configurationSource(cors.get()));
    }
  }

  private static void oauth(HttpSecurity http, JwtDecoder jwtDecoder, ApiErrors errors)
      throws Exception {
    http.oauth2ResourceServer(
        oauth2 ->
            oauth2
                .authenticationEntryPoint(errors::unauthorized)
                .accessDeniedHandler(errors::forbidden)
                .jwt(jwt -> jwt.decoder(jwtDecoder)));
  }
}
