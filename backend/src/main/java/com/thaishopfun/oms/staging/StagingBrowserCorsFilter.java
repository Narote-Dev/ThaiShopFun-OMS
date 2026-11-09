package com.thaishopfun.oms.staging;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

/**
 * Servlet-level CORS for browser routes. Security filter-chain {@link CorsFilter} instances did not
 * emit {@code Access-Control-Allow-Origin} for the staging IdP under Boot 4 + MVC path matchers.
 */
@Configuration
@Profile("staging")
class StagingBrowserCorsFilter {

  @Bean
  FilterRegistrationBean<CorsFilter> stagingBrowserCorsFilter(
      StagingProperties properties,
      @Qualifier("stagingApiCorsConfiguration") CorsConfiguration api,
      @Qualifier("stagingIdpCorsConfiguration") CorsConfiguration idp) {
    String idpBase = normalizeIdpBase(properties.getIdpBasePath());
    CorsConfigurationSource source =
        request -> {
          String path = servletPath(request);
          if (path.startsWith(idpBase)) {
            return StagingCorsConfiguration.fresh(idp);
          }
          if (path.startsWith("/api/")) {
            return StagingCorsConfiguration.fresh(api);
          }
          return null;
        };
    FilterRegistrationBean<CorsFilter> registration =
        new FilterRegistrationBean<>(new CorsFilter(source));
    registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
    return registration;
  }

  private static String normalizeIdpBase(String base) {
    if (base == null || base.isBlank()) {
      return "/staging-idp";
    }
    return base.startsWith("/") ? base : "/" + base;
  }

  private static String servletPath(HttpServletRequest request) {
    String path = request.getRequestURI();
    String context = request.getContextPath();
    if (context != null && !context.isEmpty() && path.startsWith(context)) {
      path = path.substring(context.length());
    }
    if (path.isEmpty()) {
      return "/";
    }
    return path.startsWith("/") ? path : "/" + path;
  }
}
