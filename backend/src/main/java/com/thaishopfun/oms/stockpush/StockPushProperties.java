package com.thaishopfun.oms.stockpush;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties("oms.stock.push")
public class StockPushProperties {
  private boolean enabled = true;
  private long debounceMs = 2000;
  private int batchSize = 500;

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public long getDebounceMs() {
    return debounceMs;
  }

  public void setDebounceMs(long value) {
    if (value < 2000 || value > 5000)
      throw new IllegalArgumentException("debounce-ms must be 2000..5000");
    debounceMs = value;
  }

  public int getBatchSize() {
    return batchSize;
  }

  public void setBatchSize(int value) {
    if (value < 1 || value > 500) throw new IllegalArgumentException("batch-size must be 1..500");
    batchSize = value;
  }
}
