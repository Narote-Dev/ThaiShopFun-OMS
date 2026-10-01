package com.thaishopfun.oms.order.intake;

import com.thaishopfun.oms.inbox.InboxHandler;
import com.thaishopfun.oms.inbox.InboxMessage;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!chaos & !inbox-api-test")
public class OrderCreatedHandler implements InboxHandler {

  private final OrderIntakeSupport support;

  public OrderCreatedHandler(OrderIntakeSupport support) {
    this.support = support;
  }

  @Override
  public String eventType() {
    return "order.created";
  }

  @Override
  public void handle(InboxMessage message) {
    support.handleCreated(message);
  }
}
