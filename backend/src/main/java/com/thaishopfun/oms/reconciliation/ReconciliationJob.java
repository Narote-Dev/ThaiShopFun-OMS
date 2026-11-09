package com.thaishopfun.oms.reconciliation;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
    prefix = "oms.reconciliation",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
public class ReconciliationJob {

  private static final Logger log = LoggerFactory.getLogger(ReconciliationJob.class);

  private final JdbcTemplate jdbc;
  private final ReconciliationService service;
  private final ReconciliationProperties properties;

  public ReconciliationJob(
      JdbcTemplate jdbc, ReconciliationService service, ReconciliationProperties properties) {
    this.jdbc = jdbc;
    this.service = service;
    this.properties = properties;
  }

  @Scheduled(
      cron = "${oms.reconciliation.cron:0 0 2 * * *}",
      zone = "${oms.reconciliation.zone:Asia/Bangkok}",
      scheduler = "reconciliationTaskScheduler")
  public void tick() {
    if (!properties.isEnabled()) return;
    var tenants =
        jdbc.query(
            "SELECT id FROM list_active_tenant_ids()",
            (rs, n) -> rs.getObject("id", UUID.class));
    for (UUID tenant : tenants) {
      try {
        service.run(tenant, "SCHEDULED");
      } catch (RuntimeException ex) {
        log.warn("reconciliation failed tenant_id={} error={}", tenant, ex.getClass().getName());
      }
    }
  }
}
