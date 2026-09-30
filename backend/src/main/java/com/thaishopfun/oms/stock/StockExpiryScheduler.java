package com.thaishopfun.oms.stock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs {@link StockExpiryJob} every {@code oms.stock.expiry.interval-ms} (60 s) at a fixed rate on
 * its own single-thread pool. A hold that expires just after a run's {@code now} is picked up by
 * the next run, so it is back in stock within one interval plus one run: under 2 minutes as long as
 * a run takes less than 60 s. {@link StockProperties#validate} caps the interval at 60 s.
 */
@Component
@EnableScheduling
@ConditionalOnProperty(
    prefix = "oms.stock.expiry",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
public class StockExpiryScheduler {

  static final String INTERVAL = "${oms.stock.expiry.interval-ms:60000}";

  private static final Logger log = LoggerFactory.getLogger(StockExpiryScheduler.class);

  private final StockExpiryJob job;

  public StockExpiryScheduler(StockExpiryJob job) {
    this.job = job;
  }

  @Scheduled(
      fixedRateString = INTERVAL,
      initialDelayString = INTERVAL,
      scheduler = "stockTaskScheduler")
  public void tick() {
    // Step 1: A failed run is logged; the next tick tries again. Rows are never half-expired.
    try {
      job.runOnce();
    } catch (RuntimeException ex) {
      log.error("stock expiry run failed", ex);
    }
  }
}
