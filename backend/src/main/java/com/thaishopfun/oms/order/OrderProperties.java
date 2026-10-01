package com.thaishopfun.oms.order;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "oms.order")
public class OrderProperties {

  private Duration unpaidHoldGrace = Duration.ofMinutes(10);

  public Duration getUnpaidHoldGrace() {
    return unpaidHoldGrace;
  }

  public void setUnpaidHoldGrace(Duration unpaidHoldGrace) {
    this.unpaidHoldGrace = unpaidHoldGrace;
  }
}
