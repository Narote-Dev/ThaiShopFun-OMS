package com.thaishopfun.oms.inbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Polls {@link InboxWorker} when {@code oms.inbox.worker-enabled} is true.
 *
 * <p>No scheduler name: this uses the default {@code taskScheduler} bean. The outbox publisher
 * names {@code outboxTaskScheduler}, so a long inbox tick does not share that pool.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "oms.inbox", name = "worker-enabled", havingValue = "true")
public class InboxScheduler {

  private static final Logger log = LoggerFactory.getLogger(InboxScheduler.class);

  private final InboxWorker worker;

  public InboxScheduler(InboxWorker worker) {
    this.worker = worker;
  }

  @Scheduled(fixedDelayString = "${oms.inbox.worker-delay-ms:1000}")
  public void tick() {
    // Step 1: One process polls forever. Other instances are kept off the same row by SKIP LOCKED.
    try {
      worker.processAvailable();
    } catch (RuntimeException ex) {
      log.error("inbox poll failed", ex);
    }
  }
}
