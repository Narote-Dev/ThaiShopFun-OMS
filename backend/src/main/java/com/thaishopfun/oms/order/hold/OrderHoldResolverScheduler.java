package com.thaishopfun.oms.order.hold;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@EnableScheduling
@ConditionalOnProperty(
    prefix = "oms.order.hold-resolver",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
public class OrderHoldResolverScheduler {

  static final String INTERVAL = "${oms.order.hold-resolver.interval:PT1M}";

  private static final Logger log = LoggerFactory.getLogger(OrderHoldResolverScheduler.class);

  private final OrderHoldResolverJob job;

  public OrderHoldResolverScheduler(OrderHoldResolverJob job) {
    this.job = job;
  }

  @Scheduled(
      fixedRateString = INTERVAL,
      initialDelayString = INTERVAL,
      scheduler = "orderTaskScheduler")
  public void tick() {
    try {
      job.runScheduledBatch();
    } catch (RuntimeException ex) {
      log.error("hold resolver run failed", ex);
    }
  }
}
