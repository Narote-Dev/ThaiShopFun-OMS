package com.thaishopfun.oms.stockpush;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class StockPushJob {
  private static final Logger log = LoggerFactory.getLogger(StockPushJob.class);
  private final JdbcTemplate jdbc;
  private final StockPushService service;
  private final StockPushProperties properties;

  public StockPushJob(JdbcTemplate jdbc, StockPushService service, StockPushProperties properties) {
    this.jdbc = jdbc;
    this.service = service;
    this.properties = properties;
  }

  @Scheduled(
      fixedDelayString = "${oms.stock.push.poll-delay-ms:500}",
      scheduler = "stockPushTaskScheduler")
  public void tick() {
    if (properties.isEnabled()) run(false);
  }

  @Scheduled(
      fixedDelayString = "${oms.stock.push.full-delay-ms:900000}",
      initialDelayString = "${oms.stock.push.full-delay-ms:900000}",
      scheduler = "stockPushTaskScheduler")
  public void fullPush() {
    if (properties.isEnabled()) run(true);
  }

  public void run(boolean full) {
    var accounts =
        jdbc.query(
            "SELECT * FROM list_stock_push_accounts(?)",
            (rs, n) -> new UUID[] {rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)},
            full);
    for (UUID[] account : accounts) {
      try {
        service.flush(account[0], account[1], full);
      } catch (RuntimeException ex) {
        log.warn(
            "stock push failed tenant_id={} account_id={} error={}",
            account[0],
            account[1],
            ex.getClass().getName());
      }
    }
  }
}
