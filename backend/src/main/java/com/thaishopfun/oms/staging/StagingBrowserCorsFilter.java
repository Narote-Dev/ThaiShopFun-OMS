package com.thaishopfun.oms.staging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.web.cors.CorsUtils;
import org.springframework.web.filter.OncePerRequestFilter;

/** Browser CORS for staging IdP and API routes (runs ahead of Spring Security). */
final class StagingBrowserCorsFilter extends OncePerRequestFilter {

  private final String idpBase;
  private final List<String> allowedOrigins;

  StagingBrowserCorsFilter(StagingProperties properties) {
    this.idpBase = normalizeIdpBase(properties.getIdpBasePath());
    this.allowedOrigins = properties.corsOrigins();
    if (allowedOrigins.isEmpty()) {
      throw new IllegalStateException("OMS_STAGING_CORS_ALLOWED_ORIGINS is required in staging");
    }
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    String path = servletPath(request);
    if (!path.startsWith(idpBase) && !path.startsWith("/api/")) {
      filterChain.doFilter(request, response);
      return;
    }
    String origin = request.getHeader(HttpHeaders.ORIGIN);
    boolean allowed = origin != null && allowedOrigins.contains(origin);
    if (allowed) {
      response.setHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, origin);
      response.addHeader(HttpHeaders.VARY, HttpHeaders.ORIGIN);
    }
    if (CorsUtils.isPreFlightRequest(request)) {
      if (allowed) {
        response.setHeader(
            HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, "GET,POST,PUT,PATCH,DELETE,OPTIONS");
        response.setHeader(
            HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS, "Authorization, Content-Type, Accept");
        response.setHeader(HttpHeaders.ACCESS_CONTROL_MAX_AGE, "3600");
        response.setStatus(HttpServletResponse.SC_OK);
      } else {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
      }
      return;
    }
    filterChain.doFilter(request, response);
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
