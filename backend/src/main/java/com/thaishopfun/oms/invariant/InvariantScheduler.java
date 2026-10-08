package com.thaishopfun.oms.invariant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@EnableScheduling
@ConditionalOnProperty(
    prefix = "oms.invariant",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
public class InvariantScheduler {

  private static final Logger log = LoggerFactory.getLogger(InvariantScheduler.class);

  private final InvariantJob job;

  public InvariantScheduler(InvariantJob job) {
    this.job = job;
  }

  @Scheduled(
      cron = "${oms.invariant.cron:0 30 2 * * *}",
      zone = "${oms.invariant.zone:Asia/Bangkok}",
      scheduler = "invariantTaskScheduler")
  public void tick() {
    try {
      job.runOnce();
    } catch (RuntimeException ex) {
      log.error("invariant job failed", ex);
    }
  }
}
