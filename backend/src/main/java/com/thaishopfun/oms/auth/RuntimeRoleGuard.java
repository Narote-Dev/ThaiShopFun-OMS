package com.thaishopfun.oms.auth;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The runtime pool must not bypass row-level security. Set {@code
 * oms.security.allow-rls-bypass=true} only for an explicit break-glass boot.
 */
@Component
public class RuntimeRoleGuard implements ApplicationRunner {

  private final JdbcTemplate jdbc;
  private final OmsSecurityProperties properties;

  public RuntimeRoleGuard(JdbcTemplate jdbc, OmsSecurityProperties properties) {
    this.jdbc = jdbc;
    this.properties = properties;
  }

  @Override
  public void run(ApplicationArguments args) {
    // Step 1: Read the role this pool actually connected as. Fail closed unless opted in.
    Boolean bypasses =
        jdbc.queryForObject(
            "SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname = current_user",
            Boolean.class);
    if (Boolean.TRUE.equals(bypasses) && !properties.isAllowRlsBypass()) {
      throw new IllegalStateException("Runtime database role bypasses row-level security");
    }
  }
}
