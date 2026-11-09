package com.thaishopfun.oms.staging;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.InputStream;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

class StagingDemoPasswordPolicyTest {

  @Test
  void rejectsCommittedPlaceholderBcrypt() {
    var account =
        new StagingDemoAccount(
            "a",
            "a",
            "a@x",
            "s",
            "n",
            "OWNER",
            "PRO",
            "ACTIVE",
            1,
            StagingDemoPasswordPolicy.COMMITTED_PLACEHOLDER_BCRYPT);
    assertThatThrownBy(() -> StagingDemoPasswordPolicy.validate(List.of(account)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("placeholder");
  }

  @Test
  void rejectsDuplicateHashes() {
    String hash = "$2a$10$vk7wLbE/.Fnsh2TbKduwmOXcGfASY8MsYdd.bfaZHwDCfhdIIU6i.";
    var a = new StagingDemoAccount("a", "a", "a@x", "s", "n", "OWNER", "PRO", "ACTIVE", 1, hash);
    var b = new StagingDemoAccount("b", "b", "b@x", "s", "n", "OWNER", "PRO", "ACTIVE", 1, hash);
    assertThatThrownBy(() -> StagingDemoPasswordPolicy.validate(List.of(a, b)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("reuse");
  }

  @Test
  void acceptsUniqueNonPlaceholderHashes() {
    var a =
        new StagingDemoAccount(
            "a",
            "a",
            "a@x",
            "s",
            "n",
            "OWNER",
            "PRO",
            "ACTIVE",
            1,
            "$2a$10$vk7wLbE/.Fnsh2TbKduwmOXcGfASY8MsYdd.bfaZHwDCfhdIIU6i.");
    var b =
        new StagingDemoAccount(
            "b",
            "b",
            "b@x",
            "s",
            "n",
            "OWNER",
            "PRO",
            "ACTIVE",
            1,
            "$2a$10$aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
    assertThatCode(() -> StagingDemoPasswordPolicy.validate(List.of(a, b)))
        .doesNotThrowAnyException();
  }

  @Test
  void committedClasspathDemoAccountsAreRejected() throws Exception {
    JsonMapper jsonMapper = JsonMapper.builder().build();
    try (InputStream in = new ClassPathResource("staging/demo-accounts.json").getInputStream()) {
      List<StagingDemoAccount> accounts =
          jsonMapper.readValue(in, new TypeReference<List<StagingDemoAccount>>() {});
      assertThatThrownBy(() -> StagingDemoPasswordPolicy.validate(accounts))
          .isInstanceOf(IllegalStateException.class);
    }
  }
}
