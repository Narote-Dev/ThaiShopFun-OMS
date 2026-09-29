package com.thaishopfun.oms.inbox;

import jakarta.annotation.PreDestroy;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Polls {@link InboxWorker} on its own thread when {@code oms.inbox.worker-enabled} is true.
 *
 * <p>The outbox publisher uses the shared scheduling pool. This executor is not that pool, so a
 * slow inbox poll does not block publishing.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "oms.inbox", name = "worker-enabled", havingValue = "true")
public class InboxScheduler {

  private static final Logger log = LoggerFactory.getLogger(InboxScheduler.class);

  private final InboxWorker worker;
  private final ScheduledExecutorService executor;

  public InboxScheduler(InboxWorker worker, InboxProperties properties) {
    this.worker = worker;
    this.executor =
        Executors.newSingleThreadScheduledExecutor(
            runnable -> {
              Thread thread = new Thread(runnable, "inbox-worker");
              thread.setDaemon(true);
              return thread;
            });
    long delay = Math.max(properties.getWorkerDelayMs(), 1);
    // Step 1: One process polls forever. Other instances are kept off the same row by SKIP LOCKED.
    executor.scheduleWithFixedDelay(this::tick, delay, delay, TimeUnit.MILLISECONDS);
  }

  @PreDestroy
  void stop() {
    executor.shutdownNow();
  }

  void tick() {
    try {
      worker.processAvailable();
    } catch (RuntimeException ex) {
      log.error("inbox poll failed", ex);
    }
  }
}
