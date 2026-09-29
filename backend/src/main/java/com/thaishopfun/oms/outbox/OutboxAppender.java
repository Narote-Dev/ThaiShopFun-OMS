package com.thaishopfun.oms.outbox;

import com.thaishopfun.oms.auth.UuidV7;
import com.thaishopfun.oms.tenant.TenantContext;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Writes {@code outbox_event} in the caller's transaction. There is no transaction of its own. */
@Service
public class OutboxAppender {

  static final int MAX_ENVELOPE_BYTES = 256 * 1024;

  private final JdbcTemplate jdbc;
  private final JsonMapper json;

  public OutboxAppender(JdbcTemplate jdbc, JsonMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public UUID append(OutboxDraft draft) {
    // Step 1: Refuse a call that would commit an event without the business change.
    UUID tenantId = TenantContext.requireTenantId();
    validate(draft);

    // Step 2: The envelope is the webhook body. event_id is the outbox row id.
    UUID id = UuidV7.generate();
    String shopId =
        jdbc.query(
            "SELECT tsf_shop_id FROM tenant WHERE id = ?",
            ps -> ps.setObject(1, tenantId),
            rs -> rs.next() ? rs.getString(1) : null);
    if (shopId == null) {
      throw new IllegalStateException("current tenant is not visible");
    }
    Instant occurredAt =
        draft.occurredAt() == null
            ? Instant.now().truncatedTo(ChronoUnit.SECONDS)
            : draft.occurredAt().truncatedTo(ChronoUnit.SECONDS);
    Map<String, Object> envelope = new LinkedHashMap<>();
    envelope.put("event_id", id.toString());
    envelope.put("event_type", draft.eventType());
    envelope.put("schema_version", draft.schemaVersion());
    envelope.put("occurred_at", occurredAt.toString());
    envelope.put("tsf_shop_id", shopId);
    envelope.put("aggregate_id", draft.aggregateId());
    envelope.put("aggregate_version", draft.aggregateVersion());
    envelope.put("data", dataNode(draft.data()));
    String payload = json.writeValueAsString(envelope);
    // Step 3: Reject an envelope the webhook body cannot carry. Nothing is inserted.
    if (payload.getBytes(StandardCharsets.UTF_8).length > MAX_ENVELOPE_BYTES) {
      throw new IllegalArgumentException("outbox envelope exceeds 256 KB");
    }

    // Step 4: Insert in the surrounding transaction. A later throw rolls this row back.
    jdbc.update(
        """
        INSERT INTO outbox_event (
          id, tenant_id, aggregate_type, aggregate_id, event_type, payload, status
        ) VALUES (?, ?, ?, ?, ?, ?::jsonb, 'PENDING')
        """,
        ps -> {
          ps.setObject(1, id);
          ps.setObject(2, tenantId);
          ps.setString(3, draft.aggregateType());
          ps.setString(4, draft.aggregateId());
          ps.setString(5, draft.eventType());
          ps.setString(6, payload);
        });
    return id;
  }

  private JsonNode dataNode(Object data) {
    if (data == null) {
      return json.createObjectNode();
    }
    return json.valueToTree(data);
  }

  private static void validate(OutboxDraft draft) {
    if (draft == null) {
      throw new IllegalArgumentException("outbox draft is required");
    }
    requireText(draft.aggregateType(), "aggregateType");
    requireText(draft.aggregateId(), "aggregateId");
    requireText(draft.eventType(), "eventType");
    if (draft.schemaVersion() < 1) {
      throw new IllegalArgumentException("schemaVersion must be >= 1");
    }
    if (draft.aggregateVersion() < 0) {
      throw new IllegalArgumentException("aggregateVersion must be >= 0");
    }
  }

  private static void requireText(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " is required");
    }
  }
}
