package com.thaishopfun.oms.stock;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.thaishopfun.oms.invariant.InvariantCodes;
import com.thaishopfun.oms.invariant.InvariantJob;
import com.thaishopfun.oms.invariant.PiiLogAssertions;
import com.thaishopfun.oms.invariant.SkipInvariantCheck;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

@SkipInvariantCheck("Seeds a deliberate ledger mismatch for the job metric test")
class InvariantJobTest extends StockTestBase {

  @Autowired InvariantJob job;
  @Autowired MeterRegistry meters;

  @Test
  void runOnceEmitsMetricAndIdOnlyLogs() {
    StockFixture.Shop shop = fixture.shop("ACTIVE");
    UUID sku = fixture.sku(shop, 2);
    as(
        shop,
        () -> {
          jdbc.update(
              """
              UPDATE inventory SET on_hand = on_hand + 1, stock_version = stock_version + 1
              WHERE tenant_id = ? AND sku_id = ? AND warehouse_id = ?
              """,
              shop.tenant(),
              sku,
              shop.warehouse());
          return null;
        });

    Logger logger = (Logger) LoggerFactory.getLogger(InvariantJob.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);

    var counter =
        meters
            .find(InvariantJob.VIOLATIONS_METRIC)
            .tag("code", InvariantCodes.STOCK_LEDGER_MISMATCH)
            .counter();
    double before = counter == null ? 0 : counter.count();
    int violations = job.runOnce();
    assertThat(violations).isGreaterThan(0);
    counter =
        meters
            .find(InvariantJob.VIOLATIONS_METRIC)
            .tag("code", InvariantCodes.STOCK_LEDGER_MISMATCH)
            .counter();
    assertThat(counter).isNotNull();
    assertThat(counter.count()).isGreaterThan(before);

    List<String> lines =
        appender.list.stream()
            .map(ILoggingEvent::getFormattedMessage)
            .filter(m -> m.contains("violation"))
            .toList();
    assertThat(lines).isNotEmpty();
    for (String line : lines) {
      assertThat(line).doesNotContain("@");
      assertThat(line).doesNotContain(PiiLogAssertions.SENTINEL_PHONE);
    }
    appender.stop();
  }
}
