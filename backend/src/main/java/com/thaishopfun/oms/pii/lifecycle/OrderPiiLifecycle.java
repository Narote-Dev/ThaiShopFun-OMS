package com.thaishopfun.oms.pii.lifecycle;

import com.thaishopfun.oms.order.OrderRecipientRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderPiiLifecycle {
  private final OrderRecipientRepository recipients;

  public OrderPiiLifecycle(OrderRecipientRepository recipients) {
    this.recipients = recipients;
  }

  /** Call inside the CANCELLED or future COMPLETED transition transaction, exactly once. */
  @Transactional(propagation = Propagation.MANDATORY)
  public void onOrderClosed(UUID orderId, Instant closedAt) {
    recipients.scheduleRedaction(orderId, closedAt.plus(90, ChronoUnit.DAYS));
  }
}
