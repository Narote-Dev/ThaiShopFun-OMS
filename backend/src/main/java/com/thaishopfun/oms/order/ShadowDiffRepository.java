package com.thaishopfun.oms.order;

import com.thaishopfun.oms.auth.UuidV7;
import com.thaishopfun.oms.tenant.TenantContext;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ShadowDiffRepository {

  private final JdbcTemplate jdbc;

  public ShadowDiffRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public void insertOrderDiff(
      UUID channelAccountId, String externalOrderId, String omsValueJson, Instant observedAt) {
    UUID tenantId = TenantContext.requireTenantId();
    jdbc.update(
        """
        INSERT INTO shadow_diff (
          id, tenant_id, channel_account_id, kind, ref, oms_value, channel_value, observed_at
        ) VALUES (?, ?, ?, 'ORDER', ?, ?::jsonb, NULL, ?)
        """,
        UuidV7.generate(),
        tenantId,
        channelAccountId,
        externalOrderId,
        omsValueJson,
        OffsetDateTime.ofInstant(observedAt, ZoneOffset.UTC));
  }
}
