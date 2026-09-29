package com.thaishopfun.oms.outbox;

import java.util.UUID;
import org.springframework.stereotype.Component;

/** Test seam for a kill before the HTTP call returns, or after the ack and before SENT. */
@Component
public class OutboxHooks {

  private volatile boolean crashBeforeSend;
  private volatile boolean crashAfterAck;

  public void beforeSend(UUID eventId) {
    if (crashBeforeSend) {
      throw new OutboxCrash("kill before ack");
    }
  }

  public void afterAck(UUID eventId) {
    if (crashAfterAck) {
      throw new OutboxCrash("kill after ack");
    }
  }

  public void crashBeforeSend(boolean crash) {
    this.crashBeforeSend = crash;
  }

  public void crashAfterAck(boolean crash) {
    this.crashAfterAck = crash;
  }

  public void reset() {
    crashBeforeSend = false;
    crashAfterAck = false;
  }
}
