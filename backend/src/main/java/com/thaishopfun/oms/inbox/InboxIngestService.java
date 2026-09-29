package com.thaishopfun.oms.inbox;

import com.thaishopfun.oms.auth.IdentityProvisioner;
import com.thaishopfun.oms.auth.TraceIds;
import com.thaishopfun.oms.auth.UuidV7;
import com.thaishopfun.oms.tenant.TenantContext;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
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

  public static final String MISMATCH_METRIC = "oms.inbox.payload_mismatch";
  static final String SOURCE = "TSF";
  static final int MAX_BODY_BYTES = 1024 * 1024;
  static final int UNKNOWN_SHOP_RETRY_AFTER_SECONDS = 60;

  private static final Logger log = LoggerFactory.getLogger(InboxIngestService.class);

  private final InboxSignatureVerifier signatures;
  private final IdentityProvisioner provisioner;
  private final JdbcTemplate jdbc;
  private final TransactionTemplate transactions;
  private final JsonMapper json;
  private final java.time.Clock clock;
  private final Counter mismatches;

  public InboxIngestService(
      InboxSignatureVerifier signatures,
      IdentityProvisioner provisioner,
      JdbcTemplate jdbc,
      PlatformTransactionManager transactions,
      JsonMapper json,
      java.time.Clock clock,
      MeterRegistry meters) {
    this.signatures = signatures;
    this.provisioner = provisioner;
    this.jdbc = jdbc;
    this.transactions = new TransactionTemplate(transactions);
    this.json = json;
    this.clock = clock;
    this.mismatches = Counter.builder(MISMATCH_METRIC).register(meters);
  }

  public IngestResult receive(HttpServletRequest request) {
    try {
      return accept(request);
    } catch (Rejection rejection) {
      return new IngestResult(
          rejection.status,
          error(request, rejection.code, rejection.getMessage()),
          rejection.retryAfter);
    }
  }

  private IngestResult accept(HttpServletRequest request) {
    // Step 1: Read the raw bytes. The signature covers those bytes, not a re-serialized body.
    byte[] raw = readBody(request);
    if (!signatures.valid(request.getHeader("X-Signature"), raw)) {
      throw new Rejection(401, "UNAUTHORIZED", "Invalid signature", null);
    }
    JsonNode envelope = parse(raw);
    String eventId = requiredText(envelope, "event_id", 200);
    String headerEventId = request.getHeader("X-Event-Id");
    if (headerEventId == null || !headerEventId.equals(eventId)) {
      throw new Rejection(400, "BAD_REQUEST", "X-Event-Id does not match the body", null);
    }
    String eventType = requiredText(envelope, "event_type", 100);
    if (!eventType.matches("[A-Za-z0-9._]+")) {
      throw new Rejection(400, "BAD_REQUEST", "event_type is invalid", null);
    }
    String shopId = requiredText(envelope, "tsf_shop_id", 200);
    String aggregateId = requiredText(envelope, "aggregate_id", 200);
    boolean membership = InboxEntitlementPolicy.MEMBERSHIP_CHANGED.equals(eventType);
    long aggregateVersion = version(envelope, !membership);
    JsonNode data = envelope.get("data");
    if (data != null && !data.isNull() && !data.isObject()) {
      throw new Rejection(400, "BAD_REQUEST", "data must be an object", null);
    }
    String payload = new String(raw, StandardCharsets.UTF_8);
    if (unsupportedJson(payload)) {
      throw new Rejection(400, "BAD_REQUEST", "Body contains an unsupported character", null);
    }
    byte[] hash = sha256(raw);

    // Step 2: resolve_tenant returns an id only. An unknown business event is retryable.
    UUID resolved = resolveTenant(shopId);
    if (resolved == null) {
      resolved = provisionUnknown(shopId, eventType, data);
    }
    UUID tenantId = resolved;

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
                          payload,
                          hash)));
      Map<String, String> body = new LinkedHashMap<>();
      body.put("status", inserted ? "accepted" : "duplicate");
      body.put("event_id", eventId);
      return new IngestResult(inserted ? 202 : 200, body, null);
    } catch (DataAccessException ex) {
      if (nullJson(ex)) {
        throw new Rejection(400, "BAD_REQUEST", "Body contains an unsupported character", null);
      }
      throw ex;
    } finally {
      TenantContext.clear();
    }
  }

  private UUID provisionUnknown(String shopId, String eventType, JsonNode data) {
    // Step 1: Only an active membership.changed may create the shop. Business events wait.
    if (!InboxEntitlementPolicy.MEMBERSHIP_CHANGED.equals(eventType)) {
      throw unknownShop();
    }
    MembershipPayload membership;
    try {
      membership = MembershipPayload.parse(data);
    } catch (NonRetryableInboxException ex) {
      throw new Rejection(400, "BAD_REQUEST", "membership.changed is invalid", null);
    }
    if (!membership.active(clock)) {
      throw unknownShop();
    }
    String name = membership.name == null ? shopId : membership.name;
    return provisioner.provisionShop(
        shopId, name, membership.tier, membership.status, membership.expiresAt, membership.entVer);
  }

  private static Rejection unknownShop() {
    return new Rejection(
        503, "TENANT_NOT_READY", "Shop is not registered yet", UNKNOWN_SHOP_RETRY_AFTER_SECONDS);
  }

  private byte[] readBody(HttpServletRequest request) {
    if (request.getContentLengthLong() > MAX_BODY_BYTES) {
      throw new Rejection(413, "PAYLOAD_TOO_LARGE", "Body is too large", null);
    }
    try {
      byte[] raw = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
      if (raw.length > MAX_BODY_BYTES) {
        throw new Rejection(413, "PAYLOAD_TOO_LARGE", "Body is too large", null);
      }
      return raw;
    } catch (IOException ex) {
      throw new Rejection(400, "BAD_REQUEST", "Body could not be read", null);
    }
  }

  private JsonNode parse(byte[] raw) {
    if (raw.length == 0) {
      throw new Rejection(400, "BAD_REQUEST", "Body is required", null);
    }
    try {
      JsonNode node = json.readTree(raw);
      if (node == null || !node.isObject()) {
        throw new Rejection(400, "BAD_REQUEST", "Body must be a JSON object", null);
      }
      return node;
    } catch (JacksonException ex) {
      throw new Rejection(400, "BAD_REQUEST", "Body must be a JSON object", null);
    }
  }

  private static String requiredText(JsonNode node, String field, int max) {
    JsonNode value = node.get(field);
    if (value == null || !value.isString()) {
      throw new Rejection(400, "BAD_REQUEST", field + " is required", null);
    }
    String text = value.asString();
    if (text.isBlank() || text.length() > max) {
      throw new Rejection(400, "BAD_REQUEST", field + " is required", null);
    }
    return text;
  }

  private static long version(JsonNode node, boolean required) {
    JsonNode value = node.get("aggregate_version");
    if (value == null || value.isNull()) {
      if (required) {
        throw new Rejection(400, "BAD_REQUEST", "aggregate_version is required", null);
      }
      return 0;
    }
    Long parsed = JsonLongs.exactNonNegative(value);
    if (parsed == null) {
      throw new Rejection(400, "BAD_REQUEST", "aggregate_version is invalid", null);
    }
    return parsed;
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
      String payload,
      byte[] hash) {
    List<UUID> ids =
        jdbc.query(
            """
            INSERT INTO inbox_event (
              id, tenant_id, source, event_id, event_type, aggregate_id,
              aggregate_version, payload, payload_sha256, status
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, 'RECEIVED')
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
            payload,
            hash);
    if (!ids.isEmpty()) {
      return true;
    }
    // Step 1: First payload wins. A different body is still a duplicate, and it is visible.
    byte[] stored =
        jdbc.queryForObject(
            """
            SELECT payload_sha256 FROM inbox_event
            WHERE tenant_id = ? AND source = ? AND event_id = ?
            """,
            byte[].class,
            tenantId,
            SOURCE,
            eventId);
    if (stored == null || !MessageDigest.isEqual(stored, hash)) {
      log.warn("inbox duplicate payload mismatch for event {}", eventId);
      mismatches.increment();
    }
    return false;
  }

  private static byte[] sha256(byte[] raw) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(raw);
    } catch (java.security.NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 is unavailable", ex);
    }
  }

  private static boolean unsupportedJson(String payload) {
    return payload.indexOf('\0') >= 0 || payload.toLowerCase(Locale.ROOT).contains("\\u0000");
  }

  private static boolean nullJson(Throwable ex) {
    for (Throwable current = ex; current != null; current = current.getCause()) {
      String message = current.getMessage();
      if (message != null && message.toLowerCase(Locale.ROOT).contains("u0000")) {
        return true;
      }
      if (current instanceof java.sql.SQLException sql && "22P05".equals(sql.getSQLState())) {
        return true;
      }
    }
    return false;
  }

  private static Map<String, String> error(
      HttpServletRequest request, String code, String message) {
    Map<String, String> body = new LinkedHashMap<>();
    body.put("error", code);
    body.put("message", message);
    body.put("trace_id", TraceIds.current(request));
    return body;
  }

  public record IngestResult(int httpStatus, Map<String, String> body, Integer retryAfterSeconds) {}

  private static final class Rejection extends RuntimeException {
    private final int status;
    private final String code;
    private final Integer retryAfter;

    private Rejection(int status, String code, String message, Integer retryAfter) {
      super(message);
      this.status = status;
      this.code = code;
      this.retryAfter = retryAfter;
    }
  }
}
