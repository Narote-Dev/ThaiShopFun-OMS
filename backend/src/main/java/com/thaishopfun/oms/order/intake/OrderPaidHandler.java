package com.thaishopfun.oms.order.intake;

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
public class OrderPaidHandler implements InboxHandler {

  private final OrderIntakeSupport support;

  public OrderPaidHandler(OrderIntakeSupport support) {
    this.support = support;
  }

  @Override
  public String eventType() {
    return "order.paid";
  }

  @Override
  public void handle(InboxMessage message) {
    support.handlePaid(message);
  }
}
