package com.thaishopfun.mocktsf.events;

import org.springframework.stereotype.Component;

/** When disabled, mock-tsf still mutates catalog state but does not POST inbox webhooks. */
@Component
public class WebhookDeliveryGate {

  private volatile boolean enabled = true;

  public boolean enabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }
}
