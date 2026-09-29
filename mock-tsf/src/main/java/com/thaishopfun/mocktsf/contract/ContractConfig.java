package com.thaishopfun.mocktsf.contract;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration
public class ContractConfig {

  @Bean
  FilterRegistrationBean<OncePerRequestFilter> traceFilter() {
    FilterRegistrationBean<OncePerRequestFilter> bean = new FilterRegistrationBean<>();
    bean.setFilter(
        new OncePerRequestFilter() {
          @Override
          protected void doFilterInternal(
              HttpServletRequest request, HttpServletResponse response, FilterChain chain)
              throws ServletException, IOException {
            String traceId = UUID.randomUUID().toString().replace("-", "");
            request.setAttribute(ContractResponses.TRACE_ATTRIBUTE, traceId);
            response.setHeader("X-Trace-Id", traceId);
            chain.doFilter(request, response);
          }
        });
    bean.setOrder(Ordered.HIGHEST_PRECEDENCE);
    return bean;
  }
}
