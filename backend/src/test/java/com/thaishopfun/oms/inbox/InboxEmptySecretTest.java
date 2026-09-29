package com.thaishopfun.oms.inbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.thaishopfun.oms.auth.AuthTestSupport;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class InboxEmptySecretTest {

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    AuthTestSupport.register(registry);
    registry.add("oms.inbox.hmac-secrets", () -> "");
  }

  @LocalServerPort private int port;

  @Test
  void emptySecretsRejectEverySignature() throws Exception {
    String token =
        AuthTestSupport.token(
            "tsf-checkout",
            "unused",
            "ACTIVE",
            Instant.now().plus(1, ChronoUnit.DAYS),
            1,
            "oms-internal",
            Instant.now().plusSeconds(600),
            List.of("oms"));
    byte[] body = "{\"event_id\":\"evt-empty\"}".getBytes(StandardCharsets.UTF_8);
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec("anything".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    String timestamp = Long.toString(Instant.now().getEpochSecond());
    mac.update((timestamp + ".").getBytes(StandardCharsets.UTF_8));
    mac.update(body);
    String signature =
        "t=" + timestamp + ",v1=" + java.util.HexFormat.of().formatHex(mac.doFinal());
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/internal/v1/events"))
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + token)
            .header("X-Event-Id", "evt-empty")
            .header("X-Signature", signature)
            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
            .build();
    HttpResponse<String> response =
        HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(401);
    assertThat(response.body()).contains("UNAUTHORIZED");
  }
}
