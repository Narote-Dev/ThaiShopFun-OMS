package com.thaishopfun.oms.staging;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.thaishopfun.oms.auth.AuthTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * With {@code server.forward-headers-strategy=framework}, spoofed {@code X-Forwarded-For} can
 * change {@code getRemoteAddr()}; login lockout must remain on login id.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"test", "staging"})
class StagingLoginLockoutForwardHeadersIntegrationTest {

  @Autowired MockMvc mvc;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) throws Exception {
    AuthTestSupport.registerDatabase(registry);
    StagingTestSupport.register(registry);
    registry.add("oms.staging.login-lockout-threshold", () -> "3");
    registry.add("oms.staging.login-lockout-seconds", () -> "600");
    registry.add("server.forward-headers-strategy", () -> "framework");
  }

  @Test
  void spoofedForwardedForDoesNotBypassLoginLockout() throws Exception {
    String login = "{\"login_id\":\"owner-active\",\"password\":\"wrong\"}";
    for (int i = 0; i < 3; i++) {
      mvc.perform(
              post("/staging-idp/login")
                  .header("X-Forwarded-For", "203.0.113." + i)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(login))
          .andExpect(status().isUnauthorized());
    }
    mvc.perform(
            post("/staging-idp/login")
                .header("X-Forwarded-For", "198.51.100.50")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"login_id\":\"owner-active\",\"password\":\"trial-pass\"}"))
        .andExpect(status().isTooManyRequests());
  }
}
