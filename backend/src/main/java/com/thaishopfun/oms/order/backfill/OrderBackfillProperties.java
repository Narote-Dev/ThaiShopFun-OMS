package com.thaishopfun.oms.order.backfill;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "oms.order.backfill")
public class OrderBackfillProperties {

  private boolean enabled = true;
  private Duration interval = Duration.ofMinutes(15);
  private Duration overlap = Duration.ofMinutes(2);
  private int pageLimit = 100;

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public Duration getInterval() {
    return interval;
  }

  public void setInterval(Duration interval) {
    this.interval = interval;
  }

  public Duration getOverlap() {
    return overlap;
  }

  public void setOverlap(Duration overlap) {
    this.overlap = overlap;
  }

  public int getPageLimit() {
    return pageLimit;
  }

  public void setPageLimit(int pageLimit) {
    this.pageLimit = pageLimit;
  }
}
