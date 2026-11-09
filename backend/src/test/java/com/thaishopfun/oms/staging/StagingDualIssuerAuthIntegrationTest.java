package com.thaishopfun.oms.staging;

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
class StagingDualIssuerAuthIntegrationTest {

  @Autowired MockMvc mvc;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) throws Exception {
    AuthTestSupport.registerDatabase(registry);
    StagingTestSupport.register(registry);
  }

  @Test
  void serviceTokenAcceptedOnInternalRejectedOnApi() throws Exception {
    String service = StagingTestSupport.serviceToken("tsf");

    mvc.perform(get("/internal/v1/health").header("Authorization", "Bearer " + service))
        .andExpect(status().isOk());
    mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + service))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void userTokenAcceptedOnApiRejectedOnInternal() throws Exception {
    MvcResult login =
        mvc.perform(
                post("/staging-idp/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"login_id\":\"owner-active\",\"password\":\"trial-pass\"}"))
            .andExpect(status().isOk())
            .andReturn();
    JsonNode body =
        JsonMapper.builder().build().readTree(login.getResponse().getContentAsByteArray());
    String user = body.get("access_token").asString();

    mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + user))
        .andExpect(status().isOk());
    mvc.perform(get("/internal/v1/health").header("Authorization", "Bearer " + user))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void unknownIssuerIsRejected() throws Exception {
    String token = StagingTestSupport.unknownIssuerToken();

    mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + token))
        .andExpect(status().isUnauthorized());
    mvc.perform(get("/internal/v1/health").header("Authorization", "Bearer " + token))
        .andExpect(status().isUnauthorized());
  }
}
