package com.thaishopfun.oms.stockpush;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Random;
import org.junit.jupiter.api.Test;

class StockPushUnitTest {
  @Test
  void randomChangesNeverReuseAVersionEvenWhenQuantityReturnsToAnEarlierValue() {
    Random random = new Random(15);
    long version = 0;
    String previous = null;
    Integer qty = null;
    for (int i = 0; i < 1000; i++) {
      String key = "component-version-" + i;
      int available = random.nextInt(100);
      long next = StockPushService.nextVersion(previous, qty, version, version, key, available);
      assertThat(next).isGreaterThan(version);
      assertThat(StockPushService.nextVersion(key, available, next, next, key, available))
          .isEqualTo(next);
      previous = key;
      qty = available;
      version = next;
    }
    assertThat(StockPushService.nextVersion(null, null, 0, 9000L, "remapped", 0)).isEqualTo(9001);
  }

  @Test
  void configurationKeepsDebounceAndBatchesInsideContractBounds() {
    var properties = new StockPushProperties();
    assertThat(properties.getDebounceMs()).isEqualTo(2000);
    assertThat(properties.getBatchSize()).isEqualTo(500);
    assertThatThrownBy(() -> properties.setDebounceMs(1999))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> properties.setDebounceMs(5001))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> properties.setBatchSize(0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> properties.setBatchSize(501))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
