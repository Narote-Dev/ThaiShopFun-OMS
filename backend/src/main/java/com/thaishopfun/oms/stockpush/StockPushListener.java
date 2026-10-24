package com.thaishopfun.oms.stockpush;

import com.thaishopfun.oms.stock.StockChanged;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class StockPushListener {
  private static final Logger log = LoggerFactory.getLogger(StockPushListener.class);
  private final StockPushService service;

  public StockPushListener(StockPushService service) {
    this.service = service;
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void changed(StockChanged change) {
    try {
      service.capture(change);
    } catch (RuntimeException ex) {
      // The stock transaction already committed. Do not report a failed reserve after success;
      // the full scan repairs the missing marker from inventory versions.
      log.warn(
          "stock push capture failed tenant_id={} error={}",
          change.tenantId(),
          ex.getClass().getName());
    }
  }
}
