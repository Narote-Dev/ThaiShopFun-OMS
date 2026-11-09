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
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class StagingAuthAbsentTest {

  @Autowired ApplicationContext context;

  @Autowired MockMvc mvc;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    AuthTestSupport.register(registry);
  }

  @Test
  void stagingBeansAbsentOutsideStagingProfile() {
    assertThat(context.getBeansOfType(StagingAuthConfiguration.class)).isEmpty();
    assertThat(context.getBeansOfType(StagingLoginThrottler.class)).isEmpty();
  }

  @Test
  void stagingIdpEndpointsAreNotMounted() throws Exception {
    mvc.perform(get("/staging-idp/.well-known/jwks.json")).andExpect(status().isUnauthorized());
    mvc.perform(get("/staging-idp/demo-accounts")).andExpect(status().isUnauthorized());
    mvc.perform(
            post("/staging-idp/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"login_id\":\"x\",\"password\":\"y\"}"))
        .andExpect(status().isUnauthorized());
  }
}
