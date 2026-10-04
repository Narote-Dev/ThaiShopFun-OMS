package com.thaishopfun.oms.inbox;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Shared aggregate advisory lock key format for inbox handlers and order hold resolution. */
@Component
public class InboxAggregateLock {

  public void lock(JdbcTemplate jdbc, UUID tenantId, String source, String aggregateId) {
    String key = tenantId + "\u001f" + source + "\u001f" + aggregateId;
    jdbc.query(
        "SELECT pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended(?, 11))",
        rs -> {
          rs.next();
          return null;
        },
        key);
  }

  public void lockOrder(JdbcTemplate jdbc, UUID tenantId, String externalOrderId) {
    lock(jdbc, tenantId, InboxIngestService.SOURCE, externalOrderId);
  }
}
