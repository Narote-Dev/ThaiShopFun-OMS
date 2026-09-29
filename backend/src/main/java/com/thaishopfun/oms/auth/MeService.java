package com.thaishopfun.oms.auth;

import com.thaishopfun.oms.tenant.TenantContext;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Reads {@code /me} through RLS. The filter has already set {@link TenantContext}. */
@Service
public class MeService {

  private final JdbcTemplate jdbc;

  public MeService(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Transactional
  public MeResponse current() {
    // Step 1: Both ids come from the ThreadLocal, not from a client-supplied query parameter.
    UUID tenantId = TenantContext.requireTenantId();
    UUID userId = TenantContext.requireUserId();
    MeResponse response =
        jdbc.query(
            """
            SELECT t.id, t.name, t.tsf_shop_id, t.membership_tier, t.entitlement_status,
                   t.entitlement_expires_at, t.ent_ver, m.role
            FROM tenant t
            JOIN tenant_membership m ON m.tenant_id = t.id
            WHERE t.id = ? AND m.user_id = ?
            """,
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, userId);
            },
            rs -> rs.next() ? map(rs) : null);
    if (response == null) {
      throw new IllegalStateException("current tenant is not visible");
    }
    return response;
  }

  private static MeResponse map(ResultSet rs) throws SQLException {
    OffsetDateTime expires = rs.getObject("entitlement_expires_at", OffsetDateTime.class);
    Instant expiresAt = expires == null ? null : expires.toInstant();
    return new MeResponse(
        new MeResponse.Tenant(
            rs.getObject("id", UUID.class),
            rs.getString("name"),
            rs.getString("tsf_shop_id"),
            rs.getString("membership_tier")),
        rs.getString("role"),
        new MeResponse.Entitlement(
            rs.getString("entitlement_status"), expiresAt, rs.getLong("ent_ver")));
  }
}
