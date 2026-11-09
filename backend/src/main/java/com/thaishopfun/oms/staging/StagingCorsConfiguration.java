package com.thaishopfun.oms.staging;

import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
@Profile("staging")
class StagingCorsConfiguration {

  @Bean
  CorsConfigurationSource stagingCorsConfigurationSource(StagingProperties properties) {
    List<String> origins = properties.corsOrigins();
    if (origins.isEmpty()) {
      throw new IllegalStateException("OMS_STAGING_CORS_ALLOWED_ORIGINS is required in staging");
    }
    CorsConfiguration api = new CorsConfiguration();
    api.setAllowedOrigins(origins);
    api.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
    api.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept"));
    api.setExposedHeaders(List.of("Retry-After"));
    api.setAllowCredentials(false);
    CorsConfiguration idp = new CorsConfiguration(api);
    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    String base = properties.getIdpBasePath();
    if (!base.startsWith("/")) {
      base = "/" + base;
    }
    source.registerCorsConfiguration("/api/**", api);
    source.registerCorsConfiguration(base + "/**", idp);
    return source;
  }
}
