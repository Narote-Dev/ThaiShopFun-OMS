package com.thaishopfun.oms.staging;

import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;

@Configuration
@Profile("staging")
class StagingCorsConfiguration {

  @Bean
  CorsConfiguration stagingApiCorsConfiguration(StagingProperties properties) {
    return browserCors(properties);
  }

  @Bean
  CorsConfiguration stagingIdpCorsConfiguration(StagingProperties properties) {
    return browserCors(properties);
  }

  /**
   * API filter chain serves only {@code /api/**}. Return a fixed config so CorsFilter does not
   * depend on {@link CorsConfigurationSource} path registration (which missed servlet paths in CI).
   */
  @Bean
  @Qualifier("stagingApiCorsConfigurationSource")
  CorsConfigurationSource stagingApiCorsConfigurationSource(
      @Qualifier("stagingApiCorsConfiguration") CorsConfiguration api) {
    return request -> copy(api);
  }

  /** Staging IdP chain serves only {@code /staging-idp/**} (or configured base path). */
  @Bean
  @Qualifier("stagingIdpCorsConfigurationSource")
  CorsConfigurationSource stagingIdpCorsConfigurationSource(
      @Qualifier("stagingIdpCorsConfiguration") CorsConfiguration idp) {
    return request -> copy(idp);
  }

  private static CorsConfiguration browserCors(StagingProperties properties) {
    List<String> origins = properties.corsOrigins();
    if (origins.isEmpty()) {
      throw new IllegalStateException("OMS_STAGING_CORS_ALLOWED_ORIGINS is required in staging");
    }
    CorsConfiguration config = new CorsConfiguration();
    config.setAllowedOrigins(origins);
    config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
    config.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept"));
    config.setExposedHeaders(List.of("Retry-After"));
    config.setAllowCredentials(false);
    config.setMaxAge(3600L);
    return config;
  }

  static CorsConfiguration fresh(CorsConfiguration source) {
    return copy(source);
  }

  private static CorsConfiguration copy(CorsConfiguration source) {
    CorsConfiguration copy = new CorsConfiguration();
    copy.setAllowedOrigins(source.getAllowedOrigins());
    copy.setAllowedMethods(source.getAllowedMethods());
    copy.setAllowedHeaders(source.getAllowedHeaders());
    copy.setExposedHeaders(source.getExposedHeaders());
    copy.setAllowCredentials(source.getAllowCredentials());
    copy.setMaxAge(source.getMaxAge());
    return copy;
  }
}
