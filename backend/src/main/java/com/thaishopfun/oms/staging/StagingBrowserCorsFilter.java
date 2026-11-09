package com.thaishopfun.oms.staging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.CorsProcessor;
import org.springframework.web.cors.CorsUtils;
import org.springframework.web.cors.DefaultCorsProcessor;

/**
 * Applies browser CORS for {@code /staging-idp/**} and {@code /api/**} before Spring Security.
 * Registered as a servlet {@link Component} so MockMvc and embedded Tomcat both see it.
 */
@Component
@Profile("staging")
@Order(Ordered.HIGHEST_PRECEDENCE)
class StagingBrowserCorsFilter extends org.springframework.web.filter.OncePerRequestFilter {

  private final CorsConfigurationSource source;
  private final CorsProcessor processor = new DefaultCorsProcessor();

  StagingBrowserCorsFilter(
      StagingProperties properties,
      @Qualifier("stagingApiCorsConfiguration") CorsConfiguration api,
      @Qualifier("stagingIdpCorsConfiguration") CorsConfiguration idp) {
    String idpBase = normalizeIdpBase(properties.getIdpBasePath());
    this.source =
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
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    CorsConfiguration config = source.getCorsConfiguration(request);
    if (config == null) {
      filterChain.doFilter(request, response);
      return;
    }
    boolean valid = processor.processRequest(config, request, response);
    if (valid && CorsUtils.isPreFlightRequest(request)) {
      return;
    }
    if (valid) {
      filterChain.doFilter(request, response);
    }
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
