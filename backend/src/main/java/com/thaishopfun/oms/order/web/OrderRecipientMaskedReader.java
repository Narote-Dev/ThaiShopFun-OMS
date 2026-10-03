package com.thaishopfun.oms.order.web;

import com.thaishopfun.oms.pii.PiiCipher;
import com.thaishopfun.oms.pii.PiiColumn;
import com.thaishopfun.oms.tenant.TenantContext;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Reads masked recipient fields without decrypting phone or address. */
@Component
class OrderRecipientMaskedReader {

  private final JdbcTemplate jdbc;
  private final PiiCipher cipher;

  OrderRecipientMaskedReader(JdbcTemplate jdbc, PiiCipher cipher) {
    this.jdbc = jdbc;
    this.cipher = cipher;
  }

  record MaskedRow(
      String nameMasked, String phoneMasked, String province, String postcode, String piiStatus) {}

  Optional<MaskedRow> find(UUID orderId) {
    UUID tenantId = TenantContext.requireTenantId();
    List<MaskedRow> rows =
        jdbc.query(
            """
            SELECT name_enc, phone_last4, province, postcode, pii_status
            FROM order_recipient WHERE order_id = ?
            """,
            (rs, rowNum) -> {
              String status = rs.getString("pii_status");
              if ("REDACTED".equals(status)) {
                return new MaskedRow(
                    "redacted",
                    "redacted",
                    rs.getString("province"),
                    rs.getString("postcode"),
                    status);
              }
              byte[] nameEnc = rs.getBytes("name_enc");
              String name =
                  nameEnc == null
                      ? null
                      : cipher.decrypt(nameEnc, tenantId, orderId, PiiColumn.NAME);
              String last4 = rs.getString("phone_last4");
              return new MaskedRow(
                  OrderPiiMask.maskedName(name),
                  OrderPiiMask.maskedPhone(last4),
                  rs.getString("province"),
                  rs.getString("postcode"),
                  status);
            },
            orderId);
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
  }
}
