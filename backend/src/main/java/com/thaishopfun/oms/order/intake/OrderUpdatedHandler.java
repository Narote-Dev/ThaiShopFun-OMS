package com.thaishopfun.oms.order.intake;

import com.thaishopfun.oms.inbox.InboxHandler;
import com.thaishopfun.oms.inbox.InboxMessage;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!chaos")
public class OrderUpdatedHandler implements InboxHandler {

  private final OrderIntakeSupport support;

  public OrderUpdatedHandler(OrderIntakeSupport support) {
    this.support = support;
  }

  @Override
  public String eventType() {
    return "order.updated";
  }

  @Override
  public void handle(InboxMessage message) {
    support.handleUpdated(message);
  }
}
