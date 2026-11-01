package com.thaishopfun.oms.order.returnflow;

import com.thaishopfun.oms.inbox.InboxHandler;
import com.thaishopfun.oms.inbox.InboxMessage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
    prefix = "oms.order-intake",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
public class ReturnDecidedHandler implements InboxHandler {

  private final ReturnIntakeSupport support;

  public ReturnDecidedHandler(ReturnIntakeSupport support) {
    this.support = support;
  }

  @Override
  public String eventType() {
    return "return.decided";
  }

  @Override
  public void handle(InboxMessage message) {
    support.handleDecided(message);
  }
}
