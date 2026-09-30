package com.thaishopfun.oms.outbox;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test-only crash seam for the delivery chaos suite. Lives in this package because {@link
 * OutboxHooks} is package-private. Only the chaos test registers it; production keeps the no-op
 * bean from {@link OutboxConfig}.
 *
 * <p>Crashes are keyed by event id and call count, so the same seed always kills the same events at
 * the same point, whatever the publisher thread interleaving is.
 */
public final class ChaosOutboxHooks extends OutboxHooks {

  private final Set<UUID> crashBeforeSend = ConcurrentHashMap.newKeySet();
  private final Set<UUID> crashAfterAck = ConcurrentHashMap.newKeySet();
  private final Map<UUID, AtomicInteger> sendCalls = new ConcurrentHashMap<>();
  private final Map<UUID, AtomicInteger> ackCalls = new ConcurrentHashMap<>();
  private final AtomicInteger beforeSendCrashes = new AtomicInteger();
  private final AtomicInteger afterAckCrashes = new AtomicInteger();

  /** Kill the publisher on the first send of this event, before the HTTP call. */
  public void crashBeforeFirstSend(UUID eventId) {
    crashBeforeSend.add(eventId);
  }

  /** Kill the publisher after the first 2xx for this event, before it is marked SENT. */
  public void crashAfterFirstAck(UUID eventId) {
    crashAfterAck.add(eventId);
  }

  public int beforeSendCrashes() {
    return beforeSendCrashes.get();
  }

  public int afterAckCrashes() {
    return afterAckCrashes.get();
  }

  public void reset() {
    crashBeforeSend.clear();
    crashAfterAck.clear();
    sendCalls.clear();
    ackCalls.clear();
    beforeSendCrashes.set(0);
    afterAckCrashes.set(0);
  }

  @Override
  void beforeSend(UUID eventId) {
    // Step 1: Count this send. Only call 1 of a planned event dies, so the retry goes through.
    int call = sendCalls.computeIfAbsent(eventId, ignored -> new AtomicInteger()).incrementAndGet();
    if (call == 1 && crashBeforeSend.contains(eventId)) {
      beforeSendCrashes.incrementAndGet();
      throw new OutboxCrash("chaos: kill before send");
    }
  }

  @Override
  void afterAck(UUID eventId) {
    // Step 1: The receiver already has the event. Dying here forces a duplicate delivery.
    int call = ackCalls.computeIfAbsent(eventId, ignored -> new AtomicInteger()).incrementAndGet();
    if (call == 1 && crashAfterAck.contains(eventId)) {
      afterAckCrashes.incrementAndGet();
      throw new OutboxCrash("chaos: kill after ack");
    }
  }
}
