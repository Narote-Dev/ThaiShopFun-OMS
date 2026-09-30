package com.thaishopfun.oms.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jwt.SignedJWT;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * The default {@code oms.security.accepted-token-types} allows {@code at+jwt}, {@code JWT}, and a
 * missing {@code typ}. The test profile is strict, so this context sets the permissive list.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PermissiveTokenTypeTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    AuthTestSupport.register(registry);
    registry.add("oms.security.accepted-token-types[0]", () -> "at+jwt");
    registry.add("oms.security.accepted-token-types[1]", () -> "JWT");
    registry.add("oms.security.accepted-token-types[2]", () -> "");
  }

  @LocalServerPort private int port;

  private RestClient client;

  @BeforeEach
  void client() {
    client = RestClient.builder().baseUrl("http://127.0.0.1:" + port).build();
  }

  @Test
  void jwtTypAccessTokenIsAccepted() throws Exception {
    String shopId = "shop-" + UUID.randomUUID();
    String userId = "user-" + UUID.randomUUID();
    String token = AuthTestSupport.userTokenWithTyp(userId, shopId, "JWT");
    assertThat(SignedJWT.parse(token).getHeader().getType().toString()).isEqualTo("JWT");
    HttpResult result = get("/api/v1/me", token);
    assertThat(result.status()).isEqualTo(200);
    assertThat(JSON.readTree(result.body()).path("tenant").path("tsf_shop_id").asString())
        .isEqualTo(shopId);
  }

  @Test
  void missingTypAccessTokenIsAccepted() throws Exception {
    String shopId = "shop-" + UUID.randomUUID();
    String userId = "user-" + UUID.randomUUID();
    String token = AuthTestSupport.userTokenWithTyp(userId, shopId, null);
    assertThat(SignedJWT.parse(token).getHeader().getType()).isNull();
    assertThat(get("/api/v1/me", token).status()).isEqualTo(200);
  }

  @Test
  void idTokenBearerIs401WhenJwtTypIsAllowed() throws Exception {
    String token =
        AuthTestSupport.idToken("user-" + UUID.randomUUID(), "shop-" + UUID.randomUUID());
    SignedJWT jwt = SignedJWT.parse(token);
    assertThat(jwt.getHeader().getType().toString()).isEqualTo("JWT");
    assertThat(jwt.getJWTClaimsSet().getAudience()).containsExactly("oms-web");
    HttpResult result = get("/api/v1/me", token);
    assertThat(result.status()).isEqualTo(401);
    assertThat(JSON.readTree(result.body()).path("error").asString()).isEqualTo("UNAUTHORIZED");
  }

  private HttpResult get(String path, String token) {
    return client
        .get()
        .uri(path)
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
        .exchange(
            (request, response) ->
                new HttpResult(
                    response.getStatusCode().value(),
                    new String(
                        response.getBody().readAllBytes(),
                        java.nio.charset.StandardCharsets.UTF_8)));
  }

  private record HttpResult(int status, String body) {}
}
