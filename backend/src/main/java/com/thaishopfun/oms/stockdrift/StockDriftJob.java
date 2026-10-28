package com.thaishopfun.oms.stockdrift;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class StockDriftJob {
  private static final Logger log = LoggerFactory.getLogger(StockDriftJob.class);
  private final JdbcTemplate jdbc;
  private final StockDriftService service;
  private final boolean enabled;

  public StockDriftJob(
      JdbcTemplate jdbc,
      StockDriftService service,
      @Value("${oms.stock.drift.enabled:true}") boolean enabled) {
    this.jdbc = jdbc;
    this.service = service;
    this.enabled = enabled;
  }

  @Scheduled(
      fixedDelayString = "${oms.stock.drift.interval-ms:300000}",
      initialDelayString = "${oms.stock.drift.interval-ms:300000}",
      scheduler = "stockDriftTaskScheduler")
  public void tick() {
    if (!enabled) return;
    // Existing narrow SECURITY DEFINER discovery; all reads/writes below use tenant RLS.
    var tenants =
        jdbc.query(
            "SELECT DISTINCT tenant_id FROM list_stock_push_accounts(true)",
            (rs, n) -> rs.getObject(1, UUID.class));
    for (UUID tenant : tenants) {
      try {
        service.run(tenant);
      } catch (RuntimeException ex) {
        log.warn("stock drift failed tenant_id={} error={}", tenant, ex.getClass().getName());
      }
    }
  }
}
