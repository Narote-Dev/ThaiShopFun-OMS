package com.thaishopfun.oms.order.backfill;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@EnableScheduling
@ConditionalOnProperty(
    prefix = "oms.order.backfill",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
public class OrderBackfillScheduler {

  static final String INTERVAL = "${oms.order.backfill.interval:PT15M}";

  private static final Logger log = LoggerFactory.getLogger(OrderBackfillScheduler.class);

  private final OrderBackfillJob job;

  public OrderBackfillScheduler(OrderBackfillJob job) {
    this.job = job;
  }

  @Scheduled(
      fixedRateString = INTERVAL,
      initialDelayString = INTERVAL,
      scheduler = "orderTaskScheduler")
  public void tick() {
    try {
      job.runOnce();
    } catch (RuntimeException ex) {
      log.error("order backfill scheduler failed", ex);
    }
  }
}
