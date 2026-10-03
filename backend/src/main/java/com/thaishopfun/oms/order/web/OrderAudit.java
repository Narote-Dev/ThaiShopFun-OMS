package com.thaishopfun.oms.order.web;

import com.thaishopfun.oms.auth.UuidV7;
import jakarta.servlet.http.HttpServletRequest;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tools.jackson.databind.json.JsonMapper;

/** Writes {@code audit_log} rows in the caller's transaction. No PII in payloads. */
@Component
public class OrderAudit {

  private static final String INSERT =
      """
      INSERT INTO audit_log
        (id, tenant_id, actor_type, actor_id, action, entity_type, entity_id, "before", "after", ip)
      VALUES (?, ?, 'USER', ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::inet)
      """;

  private final JdbcTemplate jdbc;
  private final JsonMapper json;

  public OrderAudit(JdbcTemplate jdbc, JsonMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  public void write(
      OrderAccess.Actor actor,
      String action,
      UUID entityId,
      Map<String, ?> before,
      Map<String, ?> after) {
    jdbc.update(
        INSERT,
        UuidV7.generate(),
        actor.tenantId(),
        actor.userId().toString(),
        action,
        "sales_order",
        entityId.toString(),
        before == null ? null : json.writeValueAsString(before),
        after == null ? null : json.writeValueAsString(after),
        currentIp());
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

  static void bindText(PreparedStatement ps, int index, String value) throws SQLException {
    if (value == null) {
      ps.setNull(index, Types.VARCHAR);
    } else {
      ps.setString(index, value);
    }
  }
}
