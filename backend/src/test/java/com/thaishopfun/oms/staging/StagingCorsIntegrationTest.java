package com.thaishopfun.oms.staging;

import static org.assertj.core.api.Assertions.assertThat;

import com.thaishopfun.oms.auth.AuthTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"test", "staging"})
class StagingCorsIntegrationTest {

  private static final String ALLOWED_ORIGIN = "http://localhost";
  private static final String BLOCKED_ORIGIN = "https://evil.example";

  @Autowired TestRestTemplate rest;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) throws Exception {
    AuthTestSupport.registerDatabase(registry);
    StagingTestSupport.register(registry);
  }

  @Test
  void stagingIdpPreflightAllowsConfiguredOrigin() {
    HttpHeaders headers = new HttpHeaders();
    headers.setOrigin(ALLOWED_ORIGIN);
    headers.setAccessControlRequestMethod(HttpMethod.POST);
    headers.add(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Content-Type");
    ResponseEntity<Void> response =
        rest.exchange(
            "/staging-idp/login", HttpMethod.OPTIONS, new HttpEntity<>(headers), Void.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getHeaders().getFirst(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN))
        .isEqualTo(ALLOWED_ORIGIN);
  }

  @Test
  void stagingIdpPreflightOmitsAllowOriginForUnknownOrigin() {
    HttpHeaders headers = new HttpHeaders();
    headers.setOrigin(BLOCKED_ORIGIN);
    headers.setAccessControlRequestMethod(HttpMethod.POST);
    headers.add(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Content-Type");
    ResponseEntity<Void> response =
        rest.exchange(
            "/staging-idp/login", HttpMethod.OPTIONS, new HttpEntity<>(headers), Void.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(response.getHeaders().getFirst(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)).isNull();
  }

  @Test
  void stagingIdpPostReflectsAllowOriginForConfiguredOrigin() {
    HttpHeaders headers = new HttpHeaders();
    headers.setOrigin(ALLOWED_ORIGIN);
    headers.setContentType(MediaType.APPLICATION_JSON);
    HttpEntity<String> entity =
        new HttpEntity<>("{\"login_id\":\"owner-active\",\"password\":\"wrong\"}", headers);
    ResponseEntity<String> response =
        rest.exchange("/staging-idp/login", HttpMethod.POST, entity, String.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(response.getHeaders().getFirst(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN))
        .isEqualTo(ALLOWED_ORIGIN);
  }
}
