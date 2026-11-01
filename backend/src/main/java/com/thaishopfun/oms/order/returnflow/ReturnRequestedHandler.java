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
public class ReturnRequestedHandler implements InboxHandler {

  private final ReturnIntakeSupport support;

  public ReturnRequestedHandler(ReturnIntakeSupport support) {
    this.support = support;
  }

  @Override
  public String eventType() {
    return "return.requested";
  }

  @Override
  public void handle(InboxMessage message) {
    support.handleRequested(message);
  }
}
