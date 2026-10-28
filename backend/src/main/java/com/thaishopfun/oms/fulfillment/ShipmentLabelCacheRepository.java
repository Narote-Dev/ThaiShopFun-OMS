package com.thaishopfun.oms.fulfillment;

import com.thaishopfun.oms.tenant.TenantContext;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class ShipmentLabelCacheRepository {

  private final JdbcTemplate jdbc;

  ShipmentLabelCacheRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  Optional<byte[]> findPdf(UUID shipmentId) {
    List<byte[]> rows =
        jdbc.query(
            """
            SELECT pdf FROM shipment_label_cache
            WHERE tenant_id = ? AND shipment_id = ?
            """,
            (rs, rowNum) -> rs.getBytes("pdf"),
            TenantContext.requireTenantId(),
            shipmentId);
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
  }

  void upsert(UUID shipmentId, String kind, byte[] pdf) {
    UUID tenantId = TenantContext.requireTenantId();
    int updated =
        jdbc.update(
            """
            UPDATE shipment_label_cache SET kind = ?, pdf = ?, created_at = now()
            WHERE tenant_id = ? AND shipment_id = ?
            """,
            kind,
            pdf,
            tenantId,
            shipmentId);
    if (updated == 0) {
      jdbc.update(
          """
          INSERT INTO shipment_label_cache (tenant_id, shipment_id, kind, pdf)
          VALUES (?, ?, ?, ?)
          """,
          tenantId,
          shipmentId,
          kind,
          pdf);
    }
  }

  int purgeExpired(Instant now) {
    return jdbc.update(
        """
        DELETE FROM shipment_label_cache slc
        USING shipment s
        WHERE slc.tenant_id = s.tenant_id
          AND slc.shipment_id = s.id
          AND s.label_cached_until IS NOT NULL
          AND s.label_cached_until <= ?
        """,
        Timestamp.from(now));
  }
}
