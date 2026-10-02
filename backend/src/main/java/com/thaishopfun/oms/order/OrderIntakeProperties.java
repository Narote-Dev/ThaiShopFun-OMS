package com.thaishopfun.oms.order;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "oms.order-intake")
public class OrderIntakeProperties {

  /** When false, production inbox handlers in {@code com.thaishopfun.oms.order.intake} are off. */
  private boolean enabled = true;

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }
}
