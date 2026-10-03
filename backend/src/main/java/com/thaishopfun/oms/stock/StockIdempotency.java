package com.thaishopfun.oms.stock;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code idempotency_key} rows for the engine, scope {@code stock.<op>}. The key row is inserted as
 * the first statement of the effect's transaction and completed in that same transaction.
 *
 * <p>A concurrent call with the same key blocks on the primary key until the first one ends. If it
 * committed, {@code ON CONFLICT DO NOTHING} skips and the next statement (READ COMMITTED) reads the
 * stored result. If it rolled back, the insert goes through and this call does the work. Taking the
 * key before any row lock keeps it outside the inventory lock order: a waiter holds nothing else.
 *
 * <p>Cleanup after 24 hours (03-data-model.md) is not part of T08.
 */
@Component
class StockIdempotency {

  static final int MAX_KEY_LENGTH = 255;

  /** A finished call: HTTP-style status and the stored envelope. */
  record Stored(int status, JsonNode body) {}

  private final JdbcTemplate jdbc;
  private final JsonMapper json;

  StockIdempotency(JdbcTemplate jdbc, JsonMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  static String requireKey(String key) {
    if (key == null || key.isBlank()) {
      throw new IllegalArgumentException("idempotency key is required");
    }
    if (key.length() > MAX_KEY_LENGTH) {
      throw new IllegalArgumentException("idempotency key is longer than " + MAX_KEY_LENGTH);
    }
    return key;
  }

  static String sha256(String canonical) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 is not available", ex);
    }
  }

  /** Null when this call owns the key. Otherwise the stored result of the earlier call. */
  Stored claim(UUID tenantId, String scope, String key, String requestHash) {
    // Step 1: Take the key. A same-key call in flight makes this wait for its commit or rollback.
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
            scope,
            key,
            requestHash);
    if (Boolean.TRUE.equals(inserted)) {
      return null;
    }
    // Step 2: Someone committed this key. A fresh statement sees that row.
    Stored stored =
        jdbc.query(
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
                throw new IdempotencyConflictException(scope);
              }
              Integer status = rs.getObject("response_status", Integer.class);
              String body = rs.getString("body");
              if (status == null || body == null) {
                throw new IllegalStateException("idempotency key has no stored result");
              }
              return new Stored(status, json.readTree(body));
            },
            tenantId,
            scope,
            key);
    return stored;
  }

  /** Stores a success. {@code result} is serialized into the envelope. */
  void complete(UUID tenantId, String scope, String key, int status, Object result) {
    Map<String, Object> envelope = new LinkedHashMap<>();
    envelope.put("error", null);
    envelope.put("result", result);
    store(tenantId, scope, key, status, envelope);
  }

  /** Stores a business failure so a replay returns the same code. */
  void fail(UUID tenantId, String scope, String key, StockError error, String message) {
    fail(tenantId, scope, key, error, message, null);
  }

  void fail(
      UUID tenantId, String scope, String key, StockError error, String message, UUID skuId) {
    Map<String, Object> envelope = new LinkedHashMap<>();
    envelope.put("error", error.name());
    envelope.put("message", message);
    if (skuId != null) {
      envelope.put("sku_id", skuId.toString());
    }
    store(tenantId, scope, key, error.status(), envelope);
  }

  /** Rebuilds a stored outcome: the result, or a {@link StockOperationException} to throw. */
  <T> Outcome<T> replay(Stored stored, Class<T> type) {
    JsonNode error = stored.body().get("error");
    if (error != null && !error.isNull()) {
      JsonNode message = stored.body().get("message");
      JsonNode sku = stored.body().get("sku_id");
      UUID skuId = null;
      if (sku != null && sku.isString() && !sku.asString().isBlank()) {
        try {
          skuId = UUID.fromString(sku.asString());
        } catch (IllegalArgumentException ignored) {
          skuId = null;
        }
      }
      return Outcome.failure(
          StockError.valueOf(error.asString()),
          message == null ? "" : message.asString(),
          skuId);
    }
    return Outcome.success(json.treeToValue(stored.body().get("result"), type));
  }

  private void store(UUID tenantId, String scope, String key, int status, Object envelope) {
    int updated =
        jdbc.update(
            """
            UPDATE idempotency_key
            SET response_status = ?, response_body = ?::jsonb
            WHERE tenant_id = ? AND scope = ? AND "key" = ?
            """,
            status,
            json.writeValueAsString(envelope),
            tenantId,
            scope,
            key);
    if (updated != 1) {
      throw new IllegalStateException("idempotency key row is missing");
    }
  }

  /** The value an engine call returns, or the business failure it throws after commit. */
  record Outcome<T>(T value, StockError error, String message, UUID skuId) {

    static <T> Outcome<T> success(T value) {
      return new Outcome<>(value, null, null, null);
    }

    static <T> Outcome<T> failure(StockError error, String message) {
      return failure(error, message, null);
    }

    static <T> Outcome<T> failure(StockError error, String message, UUID skuId) {
      return new Outcome<>(null, error, message, skuId);
    }

    T unwrap() {
      if (error != null) {
        throw new StockOperationException(error, message, skuId);
      }
      return value;
    }
  }
}
