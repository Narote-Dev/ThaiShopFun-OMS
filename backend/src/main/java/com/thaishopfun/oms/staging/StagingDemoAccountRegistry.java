package com.thaishopfun.oms.staging;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Component
@Profile("staging")
class StagingDemoAccountRegistry {

  private final Map<String, StagingDemoAccount> byLoginId;

  StagingDemoAccountRegistry(StagingProperties properties, JsonMapper jsonMapper)
      throws IOException {
    // Step 1: Classpath seed lists accounts; env JSON overrides when set. Each row needs bcrypt
    // hash only.
    List<StagingDemoAccount> accounts = loadClasspath(jsonMapper);
    if (properties.getDemoAccounts() != null && !properties.getDemoAccounts().isBlank()) {
      accounts =
          jsonMapper.readValue(
              properties.getDemoAccounts(), new TypeReference<List<StagingDemoAccount>>() {});
    }
    Map<String, StagingDemoAccount> index = new LinkedHashMap<>();
    for (StagingDemoAccount account : accounts) {
      if (account.passwordHash() == null || account.passwordHash().isBlank()) {
        throw new IllegalStateException(
            "staging demo account "
                + account.loginId()
                + " is missing password_hash (bcrypt per person)");
      }
      index.put(account.loginId().trim(), account);
    }
    this.byLoginId = Map.copyOf(index);
    if (byLoginId.isEmpty()) {
      throw new IllegalStateException("staging demo account list is empty");
    }
  }

  List<StagingDemoAccount> list() {
    return List.copyOf(byLoginId.values());
  }

  Optional<StagingDemoAccount> find(String loginId) {
    if (loginId == null || loginId.isBlank()) {
      return Optional.empty();
    }
    return Optional.ofNullable(byLoginId.get(loginId.trim()));
  }

  private static List<StagingDemoAccount> loadClasspath(JsonMapper jsonMapper) throws IOException {
    ClassPathResource resource = new ClassPathResource("staging/demo-accounts.json");
    try (InputStream in = resource.getInputStream()) {
      return jsonMapper.readValue(in, new TypeReference<List<StagingDemoAccount>>() {});
    }
  }
}
