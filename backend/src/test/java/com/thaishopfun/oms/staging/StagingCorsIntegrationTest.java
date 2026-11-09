package com.thaishopfun.oms.staging;

import static org.assertj.core.api.Assertions.assertThat;

import com.thaishopfun.oms.auth.AuthTestSupport;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"test", "staging"})
class StagingCorsIntegrationTest {

  private static final String ALLOWED_ORIGIN = "http://localhost";
  private static final String BLOCKED_ORIGIN = "https://evil.example";

  @LocalServerPort private int port;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) throws Exception {
    AuthTestSupport.registerDatabase(registry);
    StagingTestSupport.register(registry);
  }

  @Test
  void stagingIdpPreflightAllowsConfiguredOrigin() throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(preflightUri())
            .timeout(Duration.ofSeconds(10))
            .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
            .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Content-Type")
            .build();
    HttpResponse<Void> response =
        HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.discarding());
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.headers().firstValue(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN))
        .contains(ALLOWED_ORIGIN);
  }

  @Test
  void stagingIdpPreflightOmitsAllowOriginForUnknownOrigin() throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(preflightUri())
            .timeout(Duration.ofSeconds(10))
            .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
            .header(HttpHeaders.ORIGIN, BLOCKED_ORIGIN)
            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Content-Type")
            .build();
    HttpResponse<Void> response =
        HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.discarding());
    assertThat(response.statusCode()).isEqualTo(403);
    assertThat(response.headers().firstValue(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)).isEmpty();
  }

  @Test
  void stagingIdpPostReflectsAllowOriginForConfiguredOrigin() throws Exception {
    byte[] body =
        "{\"login_id\":\"owner-active\",\"password\":\"wrong\"}".getBytes(StandardCharsets.UTF_8);
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/staging-idp/login"))
            .timeout(Duration.ofSeconds(10))
            .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
            .header(HttpHeaders.CONTENT_TYPE, "application/json")
            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
            .build();
    HttpResponse<String> response =
        HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(401);
    assertThat(response.headers().firstValue(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN))
        .contains(ALLOWED_ORIGIN);
  }

  private URI preflightUri() {
    return URI.create("http://127.0.0.1:" + port + "/staging-idp/login");
  }
}
