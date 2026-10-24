package com.thaishopfun.oms.order.backfill;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Persists order-backfill state in {@code sync_cursor.cursor} as JSON: {@code
 * {"updated_since":"...","page_cursor":"..."}}. {@code last_success_at} mirrors the committed
 * watermark instant.
 */
@Repository
public class OrderSyncCursorRepository {

  static final String RESOURCE = "ORDERS";

  private final JdbcTemplate jdbc;
  private final JsonMapper json;

  public OrderSyncCursorRepository(JdbcTemplate jdbc, JsonMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  public record State(Instant updatedSince, String pageCursor) {}

  public Optional<State> load(UUID tenantId, UUID channelAccountId) {
    return jdbc
        .query(
            """
            SELECT cursor, last_success_at
            FROM sync_cursor
            WHERE tenant_id = ? AND channel_account_id = ? AND resource = ?
            """,
            (rs, row) -> {
              String raw = rs.getString("cursor");
              Instant lastSuccess =
                  rs.getTimestamp("last_success_at") == null
                      ? null
                      : rs.getTimestamp("last_success_at").toInstant();
              if (raw == null || raw.isBlank()) {
                return new State(lastSuccess, null);
              }
              JsonNode node = json.readTree(raw);
              Instant since =
                  node.hasNonNull("updated_since")
                      ? Instant.parse(node.get("updated_since").asString())
                      : lastSuccess;
              String page =
                  node.hasNonNull("page_cursor") && !node.get("page_cursor").isNull()
                      ? node.get("page_cursor").asString()
                      : null;
              return new State(since, page);
            },
            tenantId,
            channelAccountId,
            RESOURCE)
        .stream()
        .findFirst();
  }

  public void saveProgress(
      UUID tenantId, UUID channelAccountId, Instant updatedSince, String pageCursor) {
    ObjectNode body = json.createObjectNode();
    body.put("updated_since", updatedSince.toString());
    if (pageCursor != null) {
      body.put("page_cursor", pageCursor);
    } else {
      body.putNull("page_cursor");
    }
    upsert(tenantId, channelAccountId, body.toString(), null);
  }

  public void commitSuccess(UUID tenantId, UUID channelAccountId, Instant watermark) {
    ObjectNode body = json.createObjectNode();
    body.put("updated_since", watermark.toString());
    body.putNull("page_cursor");
    upsert(tenantId, channelAccountId, body.toString(), watermark);
  }

  private void upsert(
      UUID tenantId, UUID channelAccountId, String cursorJson, Instant lastSuccessAt) {
    jdbc.update(
        """
        INSERT INTO sync_cursor (tenant_id, channel_account_id, resource, cursor, last_success_at)
        VALUES (?, ?, ?, ?, ?)
        ON CONFLICT (channel_account_id, resource) DO UPDATE
        SET cursor = EXCLUDED.cursor,
            last_success_at = COALESCE(EXCLUDED.last_success_at, sync_cursor.last_success_at),
            updated_at = pg_catalog.now()
        """,
        tenantId,
        channelAccountId,
        RESOURCE,
        cursorJson,
        lastSuccessAt == null ? null : java.sql.Timestamp.from(lastSuccessAt));
  }
}
