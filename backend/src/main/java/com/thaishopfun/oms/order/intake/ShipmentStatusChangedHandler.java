package com.thaishopfun.oms.order.intake;

import com.thaishopfun.oms.fulfillment.ShipmentIntakeSupport;
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
public class ShipmentStatusChangedHandler implements InboxHandler {

  private final ShipmentIntakeSupport support;

  public ShipmentStatusChangedHandler(ShipmentIntakeSupport support) {
    this.support = support;
  }

  @Override
  public String eventType() {
    return "shipment.status_changed";
  }

  @Override
  public void handle(InboxMessage message) {
    support.handle(message);
  }
}
