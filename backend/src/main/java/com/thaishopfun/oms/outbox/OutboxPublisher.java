package com.thaishopfun.oms.outbox;

import com.thaishopfun.oms.tenant.TenantContext;
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
    properties.assertSendable();
    if (batchSize < 1 || batchSize > 1000) {
      throw new IllegalArgumentException("batch size must be between 1 and 1000");
    }
    List<OutboxStore.Claimed> claimed = store.claim(batchSize, lease);
    if (claimed.isEmpty()) {
      return 0;
    }

    // Step 2: One transaction per tenant after the claim has committed.
    Map<UUID, List<OutboxStore.Claimed>> byTenant = new LinkedHashMap<>();
    for (OutboxStore.Claimed claimedRow : claimed) {
      byTenant.computeIfAbsent(claimedRow.tenantId(), ignored -> new ArrayList<>()).add(claimedRow);
    }
    for (Map.Entry<UUID, List<OutboxStore.Claimed>> entry : byTenant.entrySet()) {
      try {
        List<OutboxStore.Held> held = new ArrayList<>();
        inTenant(entry.getKey(), () -> held.addAll(store.loadInFlight(entry.getValue())));
        for (OutboxStore.Held row : held) {
          deliver(entry.getKey(), row);
        }
      } catch (OutboxCrash crash) {
        throw crash;
      } catch (RuntimeException ex) {
        // Change: one tenant must not strand the other tenants claimed in this batch.
        log.warn(
            "outbox tenant failed tenant_id={} error={}", entry.getKey(), ex.getClass().getName());
      }
    }
    return claimed.size();
  }

  private void deliver(UUID tenantId, OutboxStore.Held row) {
    // Step 3: Lease evidence. DEBUG so a busy publisher does not flood INFO.
    log.debug(
        "outbox lease instance={} event_id={} tenant_id={} lease_until={} attempts={}",
        instanceId,
        row.id(),
        row.tenantId(),
        row.leaseUntil(),
        row.attempts());
    // Step 4: Leave the row IN_FLIGHT when the lease cannot cover this HTTP call.
    if (leaseShorterThanTimeout(row)) {
      log.debug("outbox skip send event_id={} lease_until={}", row.id(), row.leaseUntil());
      return;
    }
    OutboxHttpSender.SendResult result;
    try {
      result = attemptSend(row);
    } catch (OutboxCrash crash) {
      throw crash;
    }
    try {
      if (result.success()) {
        hooks.afterAck(row.id());
      }
      finish(tenantId, row, result);
    } catch (OutboxCrash crash) {
      throw crash;
    } catch (RuntimeException ex) {
      // Change: a finish failure must not abandon the rest of the batch. The row stays leased.
      log.warn("outbox finish failed event_id={} error={}", row.id(), ex.getClass().getName());
    }
  }

  private OutboxHttpSender.SendResult attemptSend(OutboxStore.Held row) {
    try {
      hooks.beforeSend(row.id());
      return sender.send(row);
    } catch (OutboxCrash crash) {
      throw crash;
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new OutboxCrash("publisher interrupted before ack");
    } catch (Exception ex) {
      // Change: IOException and RuntimeException follow the ladder instead of spinning IN_FLIGHT.
      log.warn("outbox send failed event_id={} error={}", row.id(), ex.getClass().getName());
      return new OutboxHttpSender.SendResult(-1, null);
    }
  }

  private boolean leaseShorterThanTimeout(OutboxStore.Held row) {
    if (row.leaseUntil() == null) {
      return true;
    }
    Duration remaining = Duration.between(Instant.now(), row.leaseUntil().toInstant());
    return remaining.compareTo(properties.getHttpTimeout()) < 0;
  }

  private void finish(UUID tenantId, OutboxStore.Held row, OutboxHttpSender.SendResult result) {
    // Step 5: 2xx is SENT. 3xx and other 4xx are DEAD. Everything else waits on the 4.4 ladder.
    inTenant(
        tenantId,
        () -> {
          if (result.success()) {
            store.markSent(row);
            return;
          }
          if (result.dead()) {
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
