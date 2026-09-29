package com.thaishopfun.oms.outbox;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class OutboxScheduler {

  private final OutboxProperties properties;
  private final OutboxPublisher publisher;

  public OutboxScheduler(OutboxProperties properties, OutboxPublisher publisher) {
    this.properties = properties;
    this.publisher = publisher;
  }

  @Scheduled(fixedDelayString = "${oms.outbox.poll-delay-ms:2000}")
  public void tick() {
    // Step 1: A blank destination must not claim rows that would then sit IN_FLIGHT.
    if (!properties.isPublisherEnabled() || !properties.destinationConfigured()) {
      return;
    }
    publisher.publishOnce();
  }
}
