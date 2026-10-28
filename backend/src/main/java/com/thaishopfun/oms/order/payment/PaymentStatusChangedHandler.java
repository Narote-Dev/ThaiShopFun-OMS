package com.thaishopfun.oms.order.payment;

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
public class PaymentStatusChangedHandler implements InboxHandler {
  private final PaymentSnapshotService payments;

  public PaymentStatusChangedHandler(PaymentSnapshotService payments) {
    this.payments = payments;
  }

  public String eventType() {
    return "payment.status_changed";
  }

  public void handle(InboxMessage message) {
    payments.handle(message);
  }
}
