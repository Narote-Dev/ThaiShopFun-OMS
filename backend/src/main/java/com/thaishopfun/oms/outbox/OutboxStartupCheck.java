package com.thaishopfun.oms.outbox;

import org.springframework.stereotype.Component;

/** Fails context startup when the outbox destination, secret, or lease cannot be used safely. */
@Component
class OutboxStartupCheck {

  OutboxStartupCheck(OutboxProperties properties) {
    // Step 1: Run once, after binding, before the scheduler claims a row.
    properties.validate();
  }
}
