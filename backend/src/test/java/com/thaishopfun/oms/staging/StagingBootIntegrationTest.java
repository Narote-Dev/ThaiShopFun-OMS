package com.thaishopfun.oms.staging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"test", "staging"})
class StagingBootIntegrationTest {

  @Autowired MockMvc mvc;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) throws Exception {
    AuthTestSupport.registerDatabase(registry);
    StagingTestSupport.register(registry);
  }

  @Test
  void stagingContextBootsAndLoginProtectsApi() throws Exception {
    mvc.perform(get("/api/v1/me")).andExpect(status().isUnauthorized());
    mvc.perform(
            post("/staging-idp/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"login_id\":\"owner-active\",\"password\":\"wrong\"}"))
        .andExpect(status().isUnauthorized());
    MvcResult login =
        mvc.perform(
                post("/staging-idp/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"login_id\":\"owner-active\",\"password\":\"trial-pass\"}"))
            .andExpect(status().isOk())
            .andReturn();
    JsonNode body =
        JsonMapper.builder().build().readTree(login.getResponse().getContentAsByteArray());
    String token = body.get("access_token").asString();
    assertThat(token).isNotBlank();
    mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk());
  }
}
