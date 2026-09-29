package com.thaishopfun.oms.inbox;

import com.thaishopfun.oms.auth.TraceIds;
import com.thaishopfun.oms.auth.UuidV7;
import com.thaishopfun.oms.tenant.TenantContext;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Verifies the webhook and inserts one inbox row. Processing is the worker's job. */
@Service
public class InboxIngestService {

  static final String SOURCE = "TSF";
  static final int MAX_BODY_BYTES = 1024 * 1024;

  private final InboxSignatureVerifier signatures;
  private final JdbcTemplate jdbc;
  private final TransactionTemplate transactions;
  private final JsonMapper json;

  public InboxIngestService(
      InboxSignatureVerifier signatures,
      JdbcTemplate jdbc,
      PlatformTransactionManager transactions,
      JsonMapper json) {
    this.signatures = signatures;
    this.jdbc = jdbc;
    this.transactions = new TransactionTemplate(transactions);
    this.json = json;
  }

  public IngestResult receive(HttpServletRequest request) {
    try {
      return accept(request);
    } catch (Rejection rejection) {
      return new IngestResult(
          rejection.status, error(request, rejection.code, rejection.getMessage()));
    }
  }

  private IngestResult accept(HttpServletRequest request) {
    // Step 1: Read the raw bytes. The signature covers those bytes, not a re-serialized body.
    byte[] raw = readBody(request);
    if (!signatures.valid(request.getHeader("X-Signature"), raw)) {
      throw new Rejection(401, "UNAUTHORIZED", "Invalid signature");
    }
    JsonNode envelope = parse(raw);
    String eventId = requiredText(envelope, "event_id", 200);
    String headerEventId = request.getHeader("X-Event-Id");
    if (headerEventId == null || !headerEventId.equals(eventId)) {
      throw new Rejection(400, "BAD_REQUEST", "X-Event-Id does not match the body");
    }
    String eventType = requiredText(envelope, "event_type", 100);
    if (!eventType.matches("[A-Za-z0-9._]+")) {
      throw new Rejection(400, "BAD_REQUEST", "event_type is invalid");
    }
    String shopId = requiredText(envelope, "tsf_shop_id", 200);
    String aggregateId = requiredText(envelope, "aggregate_id", 200);
    long aggregateVersion = version(envelope);
    JsonNode data = envelope.get("data");
    if (data != null && !data.isNull() && !data.isObject()) {
      throw new Rejection(400, "BAD_REQUEST", "data must be an object");
    }

    // Step 2: resolve_tenant returns an id only. An unknown shop is not inserted.
    UUID tenantId = resolveTenant(shopId);
    if (tenantId == null) {
      throw new Rejection(422, "TENANT_NOT_FOUND", "Shop is not registered");
    }

    // Step 3: Insert under that tenant. A conflict is a duplicate delivery, not an error.
    TenantContext.set(tenantId, null);
    try {
      boolean inserted =
          Boolean.TRUE.equals(
              transactions.execute(
                  status ->
                      insert(
                          tenantId,
                          eventId,
                          eventType,
                          aggregateId,
                          aggregateVersion,
                          new String(raw, java.nio.charset.StandardCharsets.UTF_8))));
      Map<String, String> body = new LinkedHashMap<>();
      body.put("status", inserted ? "accepted" : "duplicate");
      body.put("event_id", eventId);
      return new IngestResult(inserted ? 202 : 200, body);
    } finally {
      TenantContext.clear();
    }
  }

  private byte[] readBody(HttpServletRequest request) {
    if (request.getContentLengthLong() > MAX_BODY_BYTES) {
      throw new Rejection(413, "PAYLOAD_TOO_LARGE", "Body is too large");
    }
    try {
      byte[] raw = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
      if (raw.length > MAX_BODY_BYTES) {
        throw new Rejection(413, "PAYLOAD_TOO_LARGE", "Body is too large");
      }
      return raw;
    } catch (IOException ex) {
      throw new Rejection(400, "BAD_REQUEST", "Body could not be read");
    }
  }

  private JsonNode parse(byte[] raw) {
    if (raw.length == 0) {
      throw new Rejection(400, "BAD_REQUEST", "Body is required");
    }
    try {
      JsonNode node = json.readTree(raw);
      if (node == null || !node.isObject()) {
        throw new Rejection(400, "BAD_REQUEST", "Body must be a JSON object");
      }
      return node;
    } catch (JacksonException ex) {
      throw new Rejection(400, "BAD_REQUEST", "Body must be a JSON object");
    }
  }

  private static String requiredText(JsonNode node, String field, int max) {
    JsonNode value = node.get(field);
    if (value == null || !value.isString()) {
      throw new Rejection(400, "BAD_REQUEST", field + " is required");
    }
    String text = value.asString();
    if (text.isBlank() || text.length() > max) {
      throw new Rejection(400, "BAD_REQUEST", field + " is required");
    }
    return text;
  }

  private static long version(JsonNode node) {
    JsonNode value = node.get("aggregate_version");
    if (value == null || value.isNull()) {
      return 0;
    }
    if (!value.isIntegralNumber() || value.asLong() < 0) {
      throw new Rejection(400, "BAD_REQUEST", "aggregate_version is invalid");
    }
    return value.asLong();
  }

  private UUID resolveTenant(String shopId) {
    // Step 1: No tenant context. The definer reads tsf_shop_id and returns the id or null.
    return jdbc.query(
        "SELECT resolve_tenant(?, ?)",
        rs -> {
          if (!rs.next()) {
            return null;
          }
          return rs.getObject(1, UUID.class);
        },
        SOURCE,
        shopId);
  }

  private boolean insert(
      UUID tenantId,
      String eventId,
      String eventType,
      String aggregateId,
      long aggregateVersion,
      String payload) {
    List<UUID> ids =
        jdbc.query(
            """
            INSERT INTO inbox_event (
              id, tenant_id, source, event_id, event_type, aggregate_id,
              aggregate_version, payload, status
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, 'RECEIVED')
            ON CONFLICT (tenant_id, source, event_id) DO NOTHING
            RETURNING id
            """,
            (rs, row) -> rs.getObject(1, UUID.class),
            UuidV7.generate(),
            tenantId,
            SOURCE,
            eventId,
            eventType,
            aggregateId,
            aggregateVersion,
            payload);
    return !ids.isEmpty();
  }

  private static Map<String, String> error(
      HttpServletRequest request, String code, String message) {
    Map<String, String> body = new LinkedHashMap<>();
    body.put("error", code);
    body.put("message", message);
    body.put("trace_id", TraceIds.current(request));
    return body;
  }

  public record IngestResult(int httpStatus, Map<String, String> body) {}

  private static final class Rejection extends RuntimeException {
    private final int status;
    private final String code;

    private Rejection(int status, String code, String message) {
      super(message);
      this.status = status;
      this.code = code;
    }
  }
}
