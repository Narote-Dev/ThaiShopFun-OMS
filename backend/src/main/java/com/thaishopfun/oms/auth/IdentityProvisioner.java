package com.thaishopfun.oms.auth;

import java.sql.PreparedStatement;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * First-login upserts through the V2 definers. Runs before {@code TenantContext} is set: the
 * functions are {@code SECURITY DEFINER} and do not need {@code app.tenant_id}.
 */
@Service
public class IdentityProvisioner {

  private final JdbcTemplate jdbc;

  public IdentityProvisioner(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public record Provisioned(UUID tenantId, UUID userId, UUID membershipId) {}

  @Transactional
  public Provisioned provision(UserClaims claims) {
    // Step 1: Shop, then user, then membership. Advisory locks inside the functions use that order.
    UUID tenantId = provisionTenant(claims);
    UUID userId = upsertUser(claims);
    UUID membershipId = provisionMembership(tenantId, userId, claims.role());
    return new Provisioned(tenantId, userId, membershipId);
  }

  private UUID provisionTenant(UserClaims claims) {
    return oneId(
        (connection) -> {
          PreparedStatement statement =
              connection.prepareStatement("SELECT provision_tenant(?, ?, ?, ?, ?, ?)");
          statement.setString(1, claims.shopId());
          statement.setString(2, claims.shopName());
          statement.setString(3, claims.tier());
          statement.setString(4, claims.status());
          if (claims.expiresAt() == null) {
            statement.setNull(5, Types.TIMESTAMP_WITH_TIMEZONE);
          } else {
            statement.setObject(5, OffsetDateTime.ofInstant(claims.expiresAt(), ZoneOffset.UTC));
          }
          statement.setLong(6, claims.entVer());
          return statement;
        });
  }

  private UUID upsertUser(UserClaims claims) {
    return oneId(
        (connection) -> {
          PreparedStatement statement =
              connection.prepareStatement("SELECT upsert_app_user(?, ?, ?)");
          statement.setString(1, claims.tsfUserId());
          statement.setString(2, claims.email());
          statement.setString(3, claims.displayName());
          return statement;
        });
  }

  private UUID provisionMembership(UUID tenantId, UUID userId, String role) {
    return oneId(
        (connection) -> {
          PreparedStatement statement =
              connection.prepareStatement("SELECT provision_membership(?, ?, ?)");
          statement.setObject(1, tenantId);
          statement.setObject(2, userId);
          statement.setString(3, role);
          return statement;
        });
  }

  private UUID oneId(org.springframework.jdbc.core.PreparedStatementCreator creator) {
    UUID id =
        jdbc.query(
            creator,
            rs -> {
              if (!rs.next()) {
                return null;
              }
              return rs.getObject(1, UUID.class);
            });
    if (id == null) {
      throw new IllegalStateException("provisioning function returned no id");
    }
    return id;
  }
}
