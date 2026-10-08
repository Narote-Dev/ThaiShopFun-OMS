package com.thaishopfun.oms.listing;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "oms.listing.sync")
public class ListingSyncProperties {

  private double maxRemovalRatio = 0.5;
  private int minActiveForGuard = 10;

  public double getMaxRemovalRatio() {
    return maxRemovalRatio;
  }

  public void setMaxRemovalRatio(double maxRemovalRatio) {
    this.maxRemovalRatio = maxRemovalRatio;
  }

  public int getMinActiveForGuard() {
    return minActiveForGuard;
  }

  public void setMinActiveForGuard(int minActiveForGuard) {
    this.minActiveForGuard = minActiveForGuard;
  }
}
