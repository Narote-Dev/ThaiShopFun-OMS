package com.thaishopfun.oms.checkout;

import com.thaishopfun.oms.stock.IdempotencyConflictException;
import java.sql.ResultSet;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** HTTP-level idempotency for {@code checkout.reserve} (section 4.3). */
@Component
class CheckoutIdempotency {

  static final String SCOPE = "checkout.reserve";

  record Stored(int status, JsonNode body) {}

  private final JdbcTemplate jdbc;
  private final JsonMapper json;

  CheckoutIdempotency(JdbcTemplate jdbc, JsonMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  Stored claim(UUID tenantId, String checkoutId, String requestHash) {
    String key = CheckoutKeys.requireKey(checkoutId);
    Boolean inserted =
        jdbc.query(
            """
            INSERT INTO idempotency_key (tenant_id, scope, "key", request_hash)
            VALUES (?, ?, ?, ?)
            ON CONFLICT (tenant_id, scope, "key") DO NOTHING
            RETURNING true
            """,
            (ResultSetExtractor<Boolean>) ResultSet::next,
            tenantId,
            SCOPE,
            key,
            requestHash);
    if (Boolean.TRUE.equals(inserted)) {
      return null;
    }
    return jdbc.query(
        """
        SELECT request_hash, response_status, response_body::text AS body
        FROM idempotency_key
        WHERE tenant_id = ? AND scope = ? AND "key" = ?
        """,
        rs -> {
          if (!rs.next()) {
            throw new IllegalStateException("idempotency key conflicted but is not visible");
          }
          if (!requestHash.equals(rs.getString("request_hash"))) {
            throw new IdempotencyConflictException(SCOPE);
          }
          Integer status = rs.getObject("response_status", Integer.class);
          String body = rs.getString("body");
          if (status == null || body == null) {
            throw new IllegalStateException("idempotency key has no stored result");
          }
          return new Stored(status, json.readTree(body));
        },
        tenantId,
        SCOPE,
        key);
  }

  void complete(UUID tenantId, String checkoutId, int status, JsonNode body) {
    int updated =
        jdbc.update(
            """
            UPDATE idempotency_key
            SET response_status = ?, response_body = ?::jsonb
            WHERE tenant_id = ? AND scope = ? AND "key" = ?
            """,
            status,
            json.writeValueAsString(body),
            tenantId,
            SCOPE,
            checkoutId);
    if (updated != 1) {
      throw new IllegalStateException("idempotency key row is missing");
    }
  }
}
