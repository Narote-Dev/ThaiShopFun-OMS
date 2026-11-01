package com.thaishopfun.oms.fulfillment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
    prefix = "oms.fulfillment.label-cache-purge",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
public class LabelCachePurgeScheduler {

  private static final Logger log = LoggerFactory.getLogger(LabelCachePurgeScheduler.class);
  private final LabelCachePurgeJob job;

  public LabelCachePurgeScheduler(LabelCachePurgeJob job) {
    this.job = job;
  }

  @Scheduled(cron = "${oms.fulfillment.label-cache-purge.cron:0 15 4 * * *}", zone = "Asia/Bangkok")
  public void tick() {
    try {
      int removed = job.runOnce();
      if (removed > 0) {
        log.info("Purged {} shipment label cache row(s)", removed);
      }
    } catch (RuntimeException ex) {
      log.error("Label cache purge failed", ex);
    }
  }
}
