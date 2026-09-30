package com.thaishopfun.oms.catalog;

import com.thaishopfun.oms.auth.UuidV7;
import jakarta.servlet.http.HttpServletRequest;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes {@code audit_log} rows in the caller's transaction, so a rolled-back mutation leaves no
 * row. Catalog values carry no PII. Warehouse addresses are not copied.
 */
@Component
public class CatalogAudit {

  private static final String INSERT =
      """
      INSERT INTO audit_log
        (id, tenant_id, actor_type, actor_id, action, entity_type, entity_id, "before", "after", ip)
      VALUES (?, ?, 'USER', ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::inet)
      """;

  private final JdbcTemplate jdbc;
  private final JsonMapper json;

  public CatalogAudit(JdbcTemplate jdbc, JsonMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  public record Entry(
      String action,
      String entityType,
      UUID entityId,
      Map<String, ?> before,
      Map<String, ?> after) {}

  public void write(
      CatalogAccess.Actor actor,
      String action,
      String entityType,
      UUID entityId,
      Map<String, ?> before,
      Map<String, ?> after) {
    writeAll(actor, List.of(new Entry(action, entityType, entityId, before, after)));
  }

  public void writeAll(CatalogAccess.Actor actor, List<Entry> entries) {
    if (entries.isEmpty()) {
      return;
    }
    String ip = currentIp();
    List<String[]> json = new ArrayList<>(entries.size());
    for (Entry entry : entries) {
      json.add(new String[] {toJson(entry.before()), toJson(entry.after())});
    }
    jdbc.batchUpdate(
        INSERT,
        new BatchPreparedStatementSetter() {
          @Override
          public void setValues(PreparedStatement ps, int i) throws SQLException {
            Entry entry = entries.get(i);
            ps.setObject(1, UuidV7.generate());
            ps.setObject(2, actor.tenantId());
            ps.setString(3, actor.userId().toString());
            ps.setString(4, entry.action());
            ps.setString(5, entry.entityType());
            text(ps, 6, entry.entityId() == null ? null : entry.entityId().toString());
            text(ps, 7, json.get(i)[0]);
            text(ps, 8, json.get(i)[1]);
            text(ps, 9, ip);
          }

          @Override
          public int getBatchSize() {
            return entries.size();
          }
        });
  }

  private static void text(PreparedStatement ps, int index, String value) throws SQLException {
    if (value == null) {
      ps.setNull(index, Types.VARCHAR);
    } else {
      ps.setString(index, value);
    }
  }

  private String toJson(Map<String, ?> value) {
    return value == null ? null : json.writeValueAsString(value);
  }

  private static String currentIp() {
    RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
    if (!(attributes instanceof ServletRequestAttributes servlet)) {
      return null;
    }
    HttpServletRequest request = servlet.getRequest();
    String remote = request.getRemoteAddr();
    if (remote == null || remote.isBlank() || remote.indexOf(' ') >= 0) {
      return null;
    }
    return remote;
  }
}
