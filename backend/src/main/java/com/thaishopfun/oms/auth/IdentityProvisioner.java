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
 * JIT provisioning through the V2 definers. {@link #lookup} is read-only and runs before any
 * upsert. A stale {@code ent_ver} must be rejected by the caller with no call to {@link
 * #provision}.
 */
@Service
public class IdentityProvisioner {

  private final JdbcTemplate jdbc;

  public IdentityProvisioner(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public record LoginLookup(
      UUID tenantId, UUID userId, UUID membershipId, long entVer, String membershipStatus) {}

  public record Provisioned(UUID tenantId, UUID userId, UUID membershipId) {}

  public LoginLookup lookup(String shopId, String userId) {
    // Step 1: Definer read. No tenant setting and no row changes.
    return jdbc.query(
        "SELECT tenant_id, user_id, membership_id, ent_ver, membership_status FROM lookup_login(?, ?)",
        ps -> {
          ps.setString(1, shopId);
          ps.setString(2, userId);
        },
        rs -> {
          if (!rs.next()) {
            return null;
          }
          return new LoginLookup(
              rs.getObject("tenant_id", UUID.class),
              rs.getObject("user_id", UUID.class),
              rs.getObject("membership_id", UUID.class),
              rs.getLong("ent_ver"),
              rs.getString("membership_status"));
        });
  }

  public static boolean needsProvision(LoginLookup found, UserClaims claims) {
    if (found == null
        || found.tenantId() == null
        || found.userId() == null
        || found.membershipId() == null) {
      return true;
    }
    return found.entVer() < claims.entVer();
  }

  @Transactional
  public Provisioned provision(UserClaims claims, LoginLookup found) {
    // Step 1: Touch a row only when it is missing or the token entitlement is newer.
    boolean newer = found != null && found.tenantId() != null && found.entVer() < claims.entVer();
    UUID tenantId = found == null ? null : found.tenantId();
    UUID userId = found == null ? null : found.userId();
    UUID membershipId = found == null ? null : found.membershipId();
    if (tenantId == null || newer) {
      tenantId = provisionTenant(claims);
    }
    if (userId == null || newer) {
      userId = upsertUser(claims);
    }
    if (membershipId == null || newer) {
      membershipId = provisionMembership(tenantId, userId, claims.role());
    }
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
