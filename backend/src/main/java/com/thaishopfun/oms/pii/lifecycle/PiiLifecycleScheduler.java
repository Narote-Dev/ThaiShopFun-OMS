package com.thaishopfun.oms.pii.lifecycle;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
    prefix = "oms.pii.redaction",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
public class PiiLifecycleScheduler {
  private static final Logger log = LoggerFactory.getLogger(PiiLifecycleScheduler.class);
  private final PiiLifecycleJob job;

  public PiiLifecycleScheduler(PiiLifecycleJob job) {
    this.job = job;
  }

  @Scheduled(cron = "${oms.pii.redaction.cron:0 0 3 * * *}", zone = "Asia/Bangkok")
  public void tick() {
    try {
      job.runOnce();
    } catch (RuntimeException ex) {
      log.error("PII lifecycle job failed", ex);
    }
  }
}
