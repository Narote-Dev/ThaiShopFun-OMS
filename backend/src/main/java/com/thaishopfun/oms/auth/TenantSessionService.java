package com.thaishopfun.oms.auth;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads the tenant under RLS. {@code auth.login} is written only when provisioning actually wrote
 * (first login or a newer {@code ent_ver}), not on every request. Call only after {@code
 * TenantContext} is set so {@code doBegin} binds {@code app.tenant_id}.
 */
@Service
public class TenantSessionService {

  private final JdbcTemplate jdbc;

  public TenantSessionService(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Transactional(readOnly = true)
  public TenantSnapshot load(UUID tenantId, UUID userId) {
    TenantSnapshot snapshot =
        jdbc.query(
            """
            SELECT t.id, t.name, t.tsf_shop_id, t.membership_tier, t.entitlement_status,
                   t.entitlement_expires_at, t.ent_ver, m.role, m.user_id, m.status AS membership_status
            FROM tenant t
            JOIN tenant_membership m ON m.tenant_id = t.id
            WHERE t.id = ? AND m.user_id = ?
            """,
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, userId);
            },
            rs -> rs.next() ? map(rs) : null);
    if (snapshot == null) {
      throw new IllegalStateException("tenant is not visible in the current context");
    }
    return snapshot;
  }

  @Transactional
  public void recordLogin(UserClaims claims, TenantSnapshot snapshot, String remoteAddr) {
    // Step 3: Audit stores the user id and entitlement, never email or display name.
    String after =
        "{\"entitlement_status\":\""
            + snapshot.status()
            + "\",\"ent_ver\":"
            + snapshot.entVer()
            + "}";
    jdbc.update(
        """
        INSERT INTO audit_log
          (id, tenant_id, actor_type, actor_id, action, entity_type, entity_id, "after", ip)
        VALUES (?, ?, 'USER', ?, 'auth.login', 'app_user', ?, ?::jsonb, ?::inet)
        """,
        ps -> {
          ps.setObject(1, UuidV7.generate());
          ps.setObject(2, snapshot.tenantId());
          ps.setString(3, claims.tsfUserId());
          ps.setString(4, snapshot.userId().toString());
          ps.setString(5, after);
          String ip = ipOrNull(remoteAddr);
          if (ip == null) {
            ps.setNull(6, Types.VARCHAR);
          } else {
            ps.setString(6, ip);
          }
        });
  }

  private static TenantSnapshot map(ResultSet rs) throws SQLException {
    OffsetDateTime expires = rs.getObject("entitlement_expires_at", OffsetDateTime.class);
    return new TenantSnapshot(
        rs.getObject("id", UUID.class),
        rs.getObject("user_id", UUID.class),
        rs.getString("name"),
        rs.getString("tsf_shop_id"),
        rs.getString("membership_tier"),
        rs.getString("role"),
        rs.getString("entitlement_status"),
        rs.getString("membership_status"),
        expires == null ? null : expires.toInstant(),
        rs.getLong("ent_ver"));
  }

  private static String ipOrNull(String remoteAddr) {
    if (remoteAddr == null || remoteAddr.isBlank() || remoteAddr.indexOf(' ') >= 0) {
      return null;
    }
    return remoteAddr;
  }
}
