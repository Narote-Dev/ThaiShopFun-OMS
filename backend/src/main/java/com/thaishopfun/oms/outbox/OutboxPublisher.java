package com.thaishopfun.oms.outbox;

import com.thaishopfun.oms.tenant.TenantContext;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Claims a batch, sends it, and marks SENT. A kill leaves the row IN_FLIGHT until the lease ends.
 */
@Service
public class OutboxPublisher {

  private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

  private final OutboxStore store;
  private final OutboxHttpSender sender;
  private final OutboxRetryPolicy policy;
  private final OutboxHooks hooks;
  private final OutboxProperties properties;
  private final UUID instanceId;

  @Autowired
  public OutboxPublisher(
      OutboxStore store,
      OutboxHttpSender sender,
      OutboxRetryPolicy policy,
      OutboxHooks hooks,
      OutboxProperties properties) {
    this(store, sender, policy, hooks, properties, UUID.randomUUID());
  }

  public OutboxPublisher(
      OutboxStore store,
      OutboxHttpSender sender,
      OutboxRetryPolicy policy,
      OutboxHooks hooks,
      OutboxProperties properties,
      UUID instanceId) {
    this.store = store;
    this.sender = sender;
    this.policy = policy;
    this.hooks = hooks;
    this.properties = properties;
    this.instanceId = instanceId;
  }

  /** Another publisher process. Shares the database and the HTTP client, not the instance id. */
  public OutboxPublisher withInstance(UUID instanceId) {
    return new OutboxPublisher(store, sender, policy, hooks, properties, instanceId);
  }

  public UUID instanceId() {
    return instanceId;
  }

  public int publishOnce() {
    return publishOnce(properties.getBatchSize(), properties.getLease());
  }

  public int publishOnce(int batchSize, Duration lease) {
    // Step 1: Do not claim rows we cannot sign or deliver.
    if (!properties.destinationConfigured()) {
      throw new IllegalStateException("outbox destination is not configured");
    }
    if (batchSize < 1 || batchSize > 1000) {
      throw new IllegalArgumentException("batch size must be between 1 and 1000");
    }
    List<OutboxStore.Claimed> claimed = store.claim(batchSize, lease);
    if (claimed.isEmpty()) {
      return 0;
    }

    // Step 2: One transaction per tenant after the claim has committed.
    Map<UUID, List<UUID>> byTenant = new LinkedHashMap<>();
    for (OutboxStore.Claimed claimedRow : claimed) {
      byTenant
          .computeIfAbsent(claimedRow.tenantId(), ignored -> new ArrayList<>())
          .add(claimedRow.id());
    }
    for (Map.Entry<UUID, List<UUID>> entry : byTenant.entrySet()) {
      List<OutboxStore.Held> held = new ArrayList<>();
      inTenant(entry.getKey(), () -> held.addAll(store.loadInFlight(entry.getValue())));
      for (OutboxStore.Held row : held) {
        deliver(entry.getKey(), row);
      }
    }
    return claimed.size();
  }

  private void deliver(UUID tenantId, OutboxStore.Held row) {
    // Step 3: This line is the lease log. Two instances must not emit it for one event at once.
    log.info(
        "outbox lease instance={} event_id={} tenant_id={} lease_until={} attempts={}",
        instanceId,
        row.id(),
        row.tenantId(),
        row.leaseUntil(),
        row.attempts());
    OutboxHttpSender.SendResult result;
    try {
      hooks.beforeSend(row.id());
      result = sender.send(row);
    } catch (OutboxCrash crash) {
      throw crash;
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new OutboxCrash("publisher interrupted before ack");
    } catch (IOException ex) {
      // A timeout is a failed attempt, not a kill. Back off instead of waiting out the lease.
      finish(tenantId, row, new OutboxHttpSender.SendResult(-1, null));
      return;
    }
    if (result.success()) {
      hooks.afterAck(row.id());
    }
    finish(tenantId, row, result);
  }

  private void finish(UUID tenantId, OutboxStore.Held row, OutboxHttpSender.SendResult result) {
    // Step 4: 2xx is SENT. Other 4xx is DEAD. Everything else waits on the 4.4 ladder.
    inTenant(
        tenantId,
        () -> {
          if (result.success()) {
            store.markSent(row);
            return;
          }
          if (result.terminalClientError()) {
            store.markDead(row, result.status());
            return;
          }
          Optional<Duration> delay = policy.nextDelay(row.attempts(), result.retryAfter());
          if (delay.isEmpty()) {
            store.markDead(row, result.status());
            return;
          }
          store.scheduleRetry(row, OutboxStore.atUtc(Instant.now().plus(delay.get())));
        });
  }

  private static void inTenant(UUID tenantId, Runnable work) {
    UUID previousTenant = TenantContext.tenantId();
    UUID previousUser = TenantContext.userId();
    TenantContext.set(tenantId, null);
    try {
      work.run();
    } finally {
      if (previousTenant == null) {
        TenantContext.clear();
      } else {
        TenantContext.set(previousTenant, previousUser);
      }
    }
  }
}
