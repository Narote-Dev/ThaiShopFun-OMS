package com.thaishopfun.oms.staging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
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
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"test", "staging"})
class StagingCorsIntegrationTest {

  private static final String ALLOWED_ORIGIN = "http://localhost";
  private static final String BLOCKED_ORIGIN = "https://evil.example";

  @Autowired MockMvc mvc;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) throws Exception {
    AuthTestSupport.registerDatabase(registry);
    StagingTestSupport.register(registry);
  }

  @Test
  void stagingIdpPreflightAllowsConfiguredOrigin() throws Exception {
    MvcResult result =
        mvc.perform(
                options("/staging-idp/login")
                    .header("Origin", ALLOWED_ORIGIN)
                    .header("Access-Control-Request-Method", "POST")
                    .header("Access-Control-Request-Headers", "Content-Type"))
            .andExpect(status().isOk())
            .andReturn();
    assertThat(result.getResponse().getHeader("Access-Control-Allow-Origin"))
        .isEqualTo(ALLOWED_ORIGIN);
  }

  @Test
  void stagingIdpPreflightOmitsAllowOriginForUnknownOrigin() throws Exception {
    MvcResult result =
        mvc.perform(
                options("/staging-idp/login")
                    .header("Origin", BLOCKED_ORIGIN)
                    .header("Access-Control-Request-Method", "POST")
                    .header("Access-Control-Request-Headers", "Content-Type"))
            .andExpect(status().isForbidden())
            .andReturn();
    assertThat(result.getResponse().getHeader("Access-Control-Allow-Origin")).isNull();
  }

  @Test
  void stagingIdpPostReflectsAllowOriginForConfiguredOrigin() throws Exception {
    MvcResult result =
        mvc.perform(
                post("/staging-idp/login")
                    .header("Origin", ALLOWED_ORIGIN)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"login_id\":\"owner-active\",\"password\":\"wrong\"}"))
            .andExpect(status().isUnauthorized())
            .andReturn();
    assertThat(result.getResponse().getHeader("Access-Control-Allow-Origin"))
        .isEqualTo(ALLOWED_ORIGIN);
  }
}
