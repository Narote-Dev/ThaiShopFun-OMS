package com.thaishopfun.mocktsf.security;

import com.thaishopfun.mocktsf.MockProperties;
import com.thaishopfun.mocktsf.SigningKeys;
import com.thaishopfun.mocktsf.contract.ContractResponses;
import com.thaishopfun.mocktsf.idp.TokenIssuer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration
public class SecurityConfig {

  @Bean
  JwtDecoder jwtDecoder(MockProperties properties, SigningKeys keys) {
    NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(keys.publicKey()).build();
    decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(properties.getIssuer()));
    return decoder;
  }

  @Bean
  @Order(1)
  SecurityFilterChain omsEvents(HttpSecurity http) throws Exception {
    // HMAC is checked in the controller. OMS outbox does not send a bearer token.
    stateless(http);
    http.securityMatcher("/internal/v1/oms-events");
    http.authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
    return http.build();
  }

  @Bean
  @Order(2)
  SecurityFilterChain internalApi(
      HttpSecurity http,
      JwtDecoder jwtDecoder,
      MockProperties properties,
      ContractResponses responses)
      throws Exception {
    stateless(http);
    http.securityMatcher("/internal/**");
    http.authorizeHttpRequests(auth -> auth.anyRequest().authenticated());
    http.oauth2ResourceServer(
        oauth2 ->
            oauth2
                .authenticationEntryPoint(
                    (request, response, ex) -> write(responses, request, response))
                .jwt(jwt -> jwt.decoder(jwtDecoder)));
    http.addFilterAfter(
        new ClientFilter(responses, properties.getOmsServiceClientId()),
        BearerTokenAuthenticationFilter.class);
    return http.build();
  }

  @Bean
  @Order(3)
  SecurityFilterChain open(HttpSecurity http) throws Exception {
    stateless(http);
    http.securityMatcher("/**");
    http.authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
    return http.build();
  }

  private static void stateless(HttpSecurity http) throws Exception {
    http.csrf(AbstractHttpConfigurer::disable)
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
  }

  private static void write(
      ContractResponses responses, HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    var entity = responses.error(request, 401, "UNAUTHORIZED", "Invalid or expired token");
    response.setStatus(entity.getStatusCode().value());
    response.setContentType("application/json");
    response.setCharacterEncoding("UTF-8");
    response.getWriter().write(entity.getBody() == null ? "" : entity.getBody());
  }

  /** Section 4.7 accepts only the OMS service client, audience {@code tsf-internal}. */
  static final class ClientFilter extends OncePerRequestFilter {

    private final ContractResponses responses;
    private final String clientId;

    ClientFilter(ContractResponses responses, String clientId) {
      this.responses = responses;
      this.clientId = clientId;
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
      var authentication =
          org.springframework.security.core.context.SecurityContextHolder.getContext()
              .getAuthentication();
      if (!(authentication instanceof JwtAuthenticationToken jwtAuth)) {
        write(responses, request, response);
        return;
      }
      Jwt jwt = jwtAuth.getToken();
      List<String> audience = jwt.getAudience();
      String azp = jwt.getClaimAsString("azp");
      if (azp == null || azp.isBlank()) {
        azp = jwt.getClaimAsString("client_id");
      }
      boolean ok =
          audience != null
              && audience.contains(TokenIssuer.TSF_INTERNAL_AUDIENCE)
              && clientId.equals(azp);
      if (!ok) {
        write(responses, request, response);
        return;
      }
      chain.doFilter(request, response);
    }
  }
}
