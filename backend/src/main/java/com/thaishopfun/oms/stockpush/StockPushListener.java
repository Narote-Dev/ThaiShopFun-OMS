package com.thaishopfun.oms.stockpush;

import com.thaishopfun.oms.stock.StockChanged;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class StockPushListener {
  private final StockPushService service;

  public StockPushListener(StockPushService service) {
    this.service = service;
  }

  @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
  public void changed(StockChanged change) {
    // Join the stock transaction: AFTER_COMMIT still holds its pooled connection, so
    // REQUIRES_NEW here would starve a pool of one (or a saturated concurrent pool).
    service.capture(change);
  }
}
